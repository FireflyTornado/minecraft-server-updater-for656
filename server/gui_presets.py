"""GUI preset publication and validation, shared by the v2 and v3 routes."""
import hashlib
from pathlib import Path
import re
import zipfile

PLATFORMS = {
    'windows-x64': ('windows', 'x64'),
    'linux-x64': ('linux', 'x64'),
    'macos-x64': ('macos', 'x64'),
    'macos-arm64': ('macos', 'arm64'),
}
SAFE_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}')
SAFE_ARCHIVE = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar')
DOWNLOAD_PREFIX = '/api/v2/gui-presets/'
OFFER_FIELDS = ('path', 'sha256', 'size')


def unique_object(pairs):
    """Do not silently drop duplicate platform/configuration keys during JSON decoding."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON key: {key}')
        result[key] = value
    return result


def validate_source(value):
    if not isinstance(value, dict):
        raise ValueError('generation.gui_preset must be an object')
    preset_id, version = value.get('id'), value.get('version')
    if (not isinstance(preset_id, str) or not SAFE_ID.fullmatch(preset_id)
            or not isinstance(version, str) or not version
            or len(version) > 128 or version != version.strip()
            or any(ord(char) < 32 or ord(char) == 127 for char in version)):
        raise ValueError('generation.gui_preset requires a valid id and version')
    filename = value.get('file')
    variants = value.get('variants')
    if filename is not None:
        validate_filename(filename)
    if 'variants' in value:
        if not isinstance(variants, dict) or not variants:
            raise ValueError('gui_preset.variants must be a non-empty platform-to-filename object')
        for platform, archive in variants.items():
            if platform not in PLATFORMS:
                raise ValueError(f'unsupported GUI preset platform: {platform}')
            validate_filename(archive)
    if filename is None and variants is None:
        raise ValueError('generation.gui_preset requires file and/or variants')
    return preset_id, version, filename


def validate_filename(filename):
    if not isinstance(filename, str) or not SAFE_ARCHIVE.fullmatch(filename):
        raise ValueError('invalid GUI preset archive name')


def _archive_path(directory, filename):
    validate_filename(filename)
    root = Path(directory).resolve()
    archive = root / filename
    if archive.resolve().parent != root or not archive.is_file():
        raise ValueError(f'preset archive is missing or outside gui-presets: {filename}')
    return archive


def _sha256(archive):
    digest = hashlib.sha256()
    with open(archive, 'rb') as source:
        for chunk in iter(lambda: source.read(8192), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _properties(archive, entry, limit):
    """Read Java properties without executing any preset classes."""
    if (not isinstance(entry, str) or '\\' in entry
            or any(part in ('', '.', '..') for part in entry.split('/'))):
        raise ValueError('invalid GUI preset metadata path')
    info = archive.getinfo(entry)
    if info.is_dir() or info.file_size > limit:
        raise ValueError('GUI preset metadata is too large or invalid')
    # Java Properties.load(InputStream) uses ISO-8859-1 with Unicode escapes.
    text = archive.read(info).decode('iso-8859-1')

    def unescape(value):
        def replace(match):
            escaped = match.group(1)
            if escaped.startswith('u'):
                return chr(int(escaped[1:], 16))
            return {'t': '\t', 'n': '\n', 'r': '\r', 'f': '\f'}.get(escaped, escaped)
        return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', replace, value)

    result = {}
    pending = ''
    for physical in text.splitlines():
        line = pending + physical.lstrip(' \t\f')
        trailing = len(line) - len(line.rstrip('\\'))
        if trailing % 2:
            pending = line[:-1]
            continue
        pending = ''
        if not line or line.startswith(('#', '!')):
            continue
        match = re.match(r'((?:\\.|[^\s:=\\])*)(?:\s*[=:]\s*|\s+)?(.*)$', line)
        key, value = unescape(match[1]), unescape(match[2])
        if key in result:
            raise ValueError(f'duplicate GUI preset metadata key: {key}')
        result[key] = value
    if pending:
        raise ValueError('unfinished GUI preset metadata continuation')
    return result


def _build_archive(directory, filename, platform=None, version=None):
    archive = _archive_path(directory, filename)
    if not zipfile.is_zipfile(archive):
        raise ValueError(f'preset archive is not a valid JAR/ZIP file: {filename}')
    result = {'file': filename, 'path': DOWNLOAD_PREFIX + filename,
              'sha256': _sha256(archive), 'size': archive.stat().st_size}
    if platform is not None:
        try:
            with zipfile.ZipFile(archive) as jar:
                gui = _properties(jar, 'META-INF/mc-update-gui.properties', 16 * 1024)
                if (gui.get('preset-api') != '2' or gui.get('runtime-kind') != 'java-helper'
                        or (gui.get('target-os'), gui.get('target-arch')) != PLATFORMS[platform]
                        or gui.get('version') != version):
                    raise ValueError(f'preset metadata does not match {platform} / {version}: {filename}')
                runtime = _properties(jar, gui.get('runtime-manifest'), 32 * 1024)
                raw_minimum = runtime.get('minimum-java-version', '')
                if not re.fullmatch(r'[0-9]{1,2}', raw_minimum) or not 8 <= int(raw_minimum) <= 99:
                    raise ValueError(f'invalid minimum Java version: {filename}')
                minimum = int(raw_minimum)
        except (KeyError, zipfile.BadZipFile, UnicodeError) as error:
            raise ValueError(f'invalid preset metadata: {filename}: {error}') from error
        result.update(os=PLATFORMS[platform][0], arch=PLATFORMS[platform][1],
                      minimum_java_version=minimum)
    return result


def build_descriptor(source, directory):
    preset_id, version, filename = validate_source(source)
    result = {'id': preset_id, 'version': version}
    if filename is not None:
        result.update(_build_archive(directory, filename))
    if 'variants' in source:
        # Only declared variants are required. A Windows-only release is valid.
        result['variants'] = {platform: _build_archive(directory, archive, platform, version)
                              for platform, archive in source['variants'].items()}
    return result


def _check_published_archive(offer, directory, platform=None):
    if not isinstance(offer, dict):
        raise ValueError('invalid published GUI preset variant')
    filename = offer.get('file')
    archive = _archive_path(directory, filename)
    digest, size = offer.get('sha256'), offer.get('size')
    if (offer.get('path') != DOWNLOAD_PREFIX + filename
            or not isinstance(digest, str) or not re.fullmatch(r'[0-9a-fA-F]{64}', digest)
            or type(size) is not int or size < 0
            or archive.stat().st_size != size or _sha256(archive) != digest.lower()):
        raise ValueError(f'published preset archive no longer matches: {filename}')
    result = {key: offer[key] for key in OFFER_FIELDS}
    result['sha256'] = digest.lower()
    if platform is not None:
        minimum = offer.get('minimum_java_version')
        if ((offer.get('os'), offer.get('arch')) != PLATFORMS[platform]
                or type(minimum) is not int or not 8 <= minimum <= 99):
            raise ValueError('invalid published GUI preset platform or Java version')
        result.update(os=offer['os'], arch=offer['arch'], minimum_java_version=minimum)
    return result


def public_descriptor(record, directory):
    """Verify the entire publication before returning either API projection."""
    # Published variants contain records, whereas administrator variants contain filenames.
    source = dict(record)
    if 'variants' in record:
        if not isinstance(record['variants'], dict):
            raise ValueError('invalid published GUI preset variants')
        source['variants'] = {key: value.get('file') if isinstance(value, dict) else None
                              for key, value in record['variants'].items()}
    preset_id, version, filename = validate_source(source)
    result = {'id': preset_id, 'version': version}
    if filename is not None:
        result.update(_check_published_archive(record, directory))
    if 'variants' in record:
        result['variants'] = {platform: _check_published_archive(offer, directory, platform)
                              for platform, offer in record['variants'].items()}
    return result
