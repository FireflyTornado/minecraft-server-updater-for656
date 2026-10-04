package com.zack88604.autoupdater.infrastructure.json;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON field extraction for the updater protocol, without external dependencies.
 */
public final class JsonParser {

    private JsonParser() {
    }

    public static String getString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Decode a JSON string, including escaped quotes, newlines and Unicode. */
    public static String getDecodedString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"((?:[^\"\\\\\\x00-\\x1f]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*)\"").matcher(json);
        if (!matcher.find()) return null;
        String encoded = matcher.group(1);
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < encoded.length(); index++) {
            char value = encoded.charAt(index);
            if (value != '\\') {
                decoded.append(value);
                continue;
            }
            switch (encoded.charAt(++index)) {
                case 'b': decoded.append('\b'); break;
                case 'f': decoded.append('\f'); break;
                case 'n': decoded.append('\n'); break;
                case 'r': decoded.append('\r'); break;
                case 't': decoded.append('\t'); break;
                case 'u':
                    decoded.append((char) Integer.parseInt(encoded.substring(index + 1, index + 5), 16));
                    index += 4;
                    break;
                default: decoded.append(encoded.charAt(index));
            }
        }
        return decoded.toString();
    }

    public static int getInt(String json, String key, int defaultValue) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Fall through to the caller-provided default.
            }
        }
        return defaultValue;
    }

    public static long getLong(String json, String key, long defaultValue) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Fall through to the caller-provided default.
            }
        }
        return defaultValue;
    }

    public static String getObject(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return null;
        }
        int start = json.indexOf('{', keyIndex);
        if (start < 0) {
            return null;
        }
        int depth = 1;
        int index = start + 1;
        while (index < json.length() && depth > 0) {
            char value = json.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
            }
            index++;
        }
        return json.substring(start, index);
    }

    public static String getArray(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return null;
        }
        int start = json.indexOf('[', keyIndex);
        if (start < 0) {
            return null;
        }
        int depth = 1;
        int index = start + 1;
        while (index < json.length() && depth > 0) {
            char value = json.charAt(index);
            if (value == '[') {
                depth++;
            } else if (value == ']') {
                depth--;
            }
            index++;
        }
        return json.substring(start + 1, index - 1).trim();
    }

    public static List<String> parseStringArray(String array) {
        List<String> values = new ArrayList<>();
        if (array.isEmpty()) {
            return values;
        }
        Pattern pattern = Pattern.compile("\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(array);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        if (values.isEmpty() && !array.isEmpty()) {
            values.add("*");
        }
        return values;
    }
}
