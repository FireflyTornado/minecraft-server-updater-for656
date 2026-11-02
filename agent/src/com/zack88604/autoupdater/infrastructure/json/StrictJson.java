package com.zack88604.autoupdater.infrastructure.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, dependency-free JSON decoding for nested GUI preset descriptors. */
public final class StrictJson {
    private final String text;
    private int position;

    private StrictJson(String text) {
        if (text == null || text.length() > 64 * 1024) {
            throw new IllegalArgumentException("Missing or oversized JSON document");
        }
        this.text = text;
    }

    public static Map<String, Object> parseObject(String text) {
        StrictJson parser = new StrictJson(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != text.length() || !(result instanceof Map)) {
            throw parser.invalid();
        }
        return object(result);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    private Object value(int depth) {
        if (depth > 32) throw invalid();
        space();
        if (position >= text.length()) throw invalid();
        char ch = text.charAt(position);
        if (ch == '"') return string();
        if (ch == '{') {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                String key = string();
                space();
                if (!take(':') || result.containsKey(key)) throw invalid();
                result.put(key, value(depth + 1));
                space();
                if (take('}')) return result;
            } while (take(','));
            throw invalid();
        }
        if (ch == '[') {
            position++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do {
                result.add(value(depth + 1));
                space();
                if (take(']')) return result;
            } while (take(','));
            throw invalid();
        }
        if (text.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
        if (text.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
        if (text.startsWith("null", position)) { position += 4; return null; }
        return number();
    }

    private String string() {
        if (!take('"')) throw invalid();
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char ch = text.charAt(position++);
            if (ch == '"') return result.toString();
            if (ch < 32) throw invalid();
            if (ch != '\\') { result.append(ch); continue; }
            if (position >= text.length()) throw invalid();
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"': case '\\': case '/': result.append(escaped); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (position + 4 > text.length()) throw invalid();
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        char hex = text.charAt(position++);
                        int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                        if (digit < 0) throw invalid();
                        code = code * 16 + digit;
                    }
                    result.append((char) code);
                    break;
                default: throw invalid();
            }
        }
        throw invalid();
    }

    private Object number() {
        int start = position;
        take('-');
        if (!take('0')) digits();
        boolean integer = true;
        if (take('.')) { integer = false; digits(); }
        if (take('e') || take('E')) {
            integer = false;
            if (!take('+')) take('-');
            digits();
        }
        try {
            String raw = text.substring(start, position);
            if (integer) return Long.valueOf(raw);
            return new BigDecimal(raw);
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    private void digits() {
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0'
                && text.charAt(position) <= '9') position++;
        if (start == position) throw invalid();
    }

    private boolean take(char ch) {
        if (position < text.length() && text.charAt(position) == ch) {
            position++;
            return true;
        }
        return false;
    }

    private void space() {
        while (position < text.length()) {
            char ch = text.charAt(position);
            if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') return;
            position++;
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid JSON near offset " + position);
    }
}
