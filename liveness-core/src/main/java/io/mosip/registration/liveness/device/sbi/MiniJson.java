package io.mosip.registration.liveness.device.sbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for SBI responses, so the core keeps no dependencies. Objects become
 * {@code Map<String, Object>}, arrays {@code List<Object>}, numbers {@code Double}, plus String,
 * Boolean and null. Input size is bounded by the caller.
 */
final class MiniJson {

    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    /** @throws IllegalArgumentException on malformed input */
    static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWhitespace();
        Object value = p.value(0);
        p.skipWhitespace();
        if (p.i != json.length()) {
            throw p.error("trailing characters");
        }
        return value;
    }

    /** Depth-first search for the first value stored under {@code key}, or null. */
    static Object find(Object node, String key) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (map.containsKey(key)) {
                return map.get(key);
            }
            for (Object v : map.values()) {
                Object found = find(v, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                Object found = find(v, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A string field of an object (numbers are rendered without a trailing ".0"), or "" if absent. */
    static String string(Map<?, ?> object, String key) {
        Object v = object.get(key);
        if (v == null) {
            return "";
        }
        if (v instanceof Double && (Double) v == Math.rint((Double) v)) {
            return Long.toString(((Double) v).longValue());
        }
        return v.toString();
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skipWhitespace();
        if (peek('}')) {
            i++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw error("expected a key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                i++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        i++;
        skipWhitespace();
        if (peek(']')) {
            i++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                i++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw error("bad unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    i += 4;
                    break;
                default:
                    sb.append(e); // \" \\ \/
            }
        }
        throw error("unterminated string");
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, i)) {
            throw error("unexpected token");
        }
        i += word.length();
        return value;
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw error("unexpected character '" + s.charAt(i) + "'");
        }
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private void skipWhitespace() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid JSON at " + i + ": " + what);
    }
}
