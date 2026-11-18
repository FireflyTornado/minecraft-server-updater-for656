"""Sign already-built updater JARs with an offline Ed25519 release key."""

import argparse
import base64
import hashlib
from pathlib import Path
import re
import zipfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


KEY_ENTRY = 'META-INF/mc-update-release-public-key.txt'


def sign_artifact(jar, role, version, private_key, public_der):
    with zipfile.ZipFile(jar) as archive:
        embedded_key = base64.b64decode(archive.read(KEY_ENTRY).strip(), validate=True)
    if embedded_key != public_der:
        raise ValueError(f'{jar.name} pins a different release key')

    contents = jar.read_bytes()
    digest = hashlib.sha256(contents).hexdigest()
    payload = f'mc-update-release-v1\n{role}\n{version}\n{len(contents)}\n{digest}\n'
    signature = base64.b64encode(private_key.sign(payload.encode('ascii'))).decode('ascii')
    directory = jar.parent / '.mc-update' / 'releases'
    return directory / f'.mc-update-release-{digest}.sig', f'{version}:{signature}\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key', type=Path, required=True, help='Ed25519 private key in PEM format')
    parser.add_argument('--version', required=True, help='Release version recorded in each signature')
    parser.add_argument('--directory', type=Path, default=Path(__file__).resolve().parent,
                        help='Directory containing both built updater JARs')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,63}', args.version):
        parser.error('Invalid release version')

    try:
        key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
        if not isinstance(key, Ed25519PrivateKey):
            raise ValueError('The release key must be Ed25519')
        public_der = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        artifacts = (('UpdateAgent.jar', 'launcher'), ('UpdateAgent_core.jar', 'core'))
        proofs = [sign_artifact(args.directory / name, role, args.version, key, public_der)
                  for name, role in artifacts]
        for output, proof in proofs:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(proof, encoding='ascii', newline='\n')
            print(output.relative_to(args.directory))
    except (OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
