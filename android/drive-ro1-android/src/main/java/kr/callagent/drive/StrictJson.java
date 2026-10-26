package kr.callagent.drive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded JSON with duplicate-key and lossy UTF-8 rejection. No external dependency. */
public final class StrictJson {
    private StrictJson() {}
    public static final int MAX_BYTES = 262_144;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ITEMS = 4_096;
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;

    public static Object parse(byte[] bytes) { return parse(bytes, false); }
    /** Explicit outer v1 cap; the v0 entry point and opaque context limits stay unchanged. */
    public static Object parseReadOnlyEnvelope(byte[] bytes) { return parse(bytes, false, 524_288); }
    /** Validates an opaque context JSON string without canonicalizing/changing its bytes. */
    public static Map<String, Object> context(String raw) {
        stringify(raw); // validate original Unicode before UTF-8 encoding
        return object(parse(raw.getBytes(StandardCharsets.UTF_8), true));
    }
    /** Raw read-only member strings keep integer tokens distinct from decimal/exponent tokens. */
    public static Map<String,Object> readOnlyInner(String raw) {
        stringify(raw);
        return object(parse(raw.getBytes(StandardCharsets.UTF_8),true,MAX_BYTES,true));
    }
    /** Strict original artifact decoding before any conversion to a Java String. */
    public static Map<String,Object> readOnlyArtifact(byte[] raw) {
        return object(parse(raw,true,MAX_BYTES,true));
    }
    private static Object parse(byte[] bytes, boolean context) {
        return parse(bytes, context, MAX_BYTES);
    }
    private static Object parse(byte[] bytes, boolean context, int cap) {
        return parse(bytes,context,cap,false);
    }
    private static Object parse(byte[] bytes, boolean context, int cap, boolean innerNumberTypes) {
        if (bytes.length > cap) throw new IllegalArgumentException("json_too_large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid_utf8");
        }
        Parser p = new Parser(text, context, innerNumberTypes);
        Object value = p.value(0);
        p.space();
        if (p.at != text.length()) throw new IllegalArgumentException("trailing_json");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("expected_object");
        return (Map<String, Object>) value;
    }

    public static byte[] bytes(Object value) {
        return stringify(value).getBytes(StandardCharsets.UTF_8);
    }

    /** Sorted object keys; strings remain exact, without normalization. */
    public static String stringify(Object value) {
        StringBuilder b = new StringBuilder();
        append(b, value, 0);
        return b.toString();
    }

    private static void append(StringBuilder b, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("json_depth");
        if (v == null) b.append("null");
        else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Boolean) b.append(v);
        else if (v instanceof Long || v instanceof Integer) {
            long number = ((Number) v).longValue();
            if (number < 0 || number > MAX_INTEGER) throw new IllegalArgumentException("unsafe_integer");
            b.append(number);
        }
        else if (v instanceof Map) {
            Map<String, Object> m = new TreeMap<>(object(v));
            if (m.size() > MAX_ITEMS) throw new IllegalArgumentException("json_items");
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : m.entrySet()) {
                asciiKey(entry.getKey());
                if (!first) b.append(',');
                first = false;
                quote(b, entry.getKey()); b.append(':'); append(b, entry.getValue(), depth + 1);
            }
            b.append('}');
        } else if (v instanceof List) {
            List<?> values = (List<?>) v;
            if (values.size() > MAX_ITEMS) throw new IllegalArgumentException("json_items");
            b.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i != 0) b.append(',');
                append(b, values.get(i), depth + 1);
            }
            b.append(']');
        } else throw new IllegalArgumentException("unsupported_json_type");
    }

    private static void asciiKey(String key) {
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) > 127)
            throw new IllegalArgumentException("non_ascii_object_key");
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) {
                        b.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int j = hex.length(); j < 4; j++) b.append('0');
                        b.append(hex);
                    } else if (Character.isHighSurrogate(c)) {
                        if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
                            throw new IllegalArgumentException("invalid_surrogate");
                        b.append(c).append(s.charAt(i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("invalid_surrogate");
                    } else b.append(c);
            }
        }
        b.append('"');
    }

    private static final class Parser {
        final String s; final boolean context, innerNumberTypes; int at; int items;
        Parser(String s, boolean context, boolean innerNumberTypes) { this.s=s; this.context=context;this.innerNumberTypes=innerNumberTypes; }
        void space() {
            while (at < s.length() && (s.charAt(at) == ' ' || s.charAt(at) == '\n'
                    || s.charAt(at) == '\r' || s.charAt(at) == '\t')) at++;
        }
        char take() {
            if (at >= s.length()) throw bad();
            return s.charAt(at++);
        }
        void expect(char c) { if (take() != c) throw bad(); }
        IllegalArgumentException bad() { return new IllegalArgumentException("invalid_json"); }
        Object value(int depth) {
            if (depth > MAX_DEPTH || ++items > MAX_ITEMS) throw bad();
            space(); if (at >= s.length()) throw bad();
            char c = s.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++; space(); Map<String, Object> map = new LinkedHashMap<>();
                if (at < s.length() && s.charAt(at) == '}') { at++; return map; }
                while (true) {
                    space(); String key = string(); space(); expect(':');
                    if (!context) asciiKey(key);
                    if (map.containsKey(key)) throw new IllegalArgumentException("duplicate_json_key");
                    map.put(key, value(depth + 1)); space(); char end = take();
                    if (end == '}') return map;
                    if (end != ',') throw bad();
                }
            }
            if (c == '[') {
                at++; space(); List<Object> list = new ArrayList<>();
                if (at < s.length() && s.charAt(at) == ']') { at++; return list; }
                while (true) {
                    list.add(value(depth + 1)); space(); char end = take();
                    if (end == ']') return list;
                    if (end != ',') throw bad();
                }
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (s.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : literal.equals("true");
                }
            }
            int start = at;
            if (c == '-') at++;
            if (at >= s.length() || s.charAt(at) < '0' || s.charAt(at) > '9') throw bad();
            if (s.charAt(at) == '0') at++;
            else while (at < s.length() && s.charAt(at) >= '0' && s.charAt(at) <= '9') at++;
            if (context) {
                if (at < s.length() && s.charAt(at) == '.') {
                    at++; int fraction = at;
                    while (at < s.length() && s.charAt(at) >= '0' && s.charAt(at) <= '9') at++;
                    if (at == fraction) throw bad();
                }
                if (at < s.length() && (s.charAt(at) == 'e' || s.charAt(at) == 'E')) {
                    at++;
                    if (at < s.length() && (s.charAt(at) == '+' || s.charAt(at) == '-')) at++;
                    int exponent = at;
                    while (at < s.length() && s.charAt(at) >= '0' && s.charAt(at) <= '9') at++;
                    if (at == exponent) throw bad();
                }
                try {
                    String token=s.substring(start,at);
                    if(innerNumberTypes && token.indexOf('.')<0 && token.indexOf('e')<0 && token.indexOf('E')<0) return new java.math.BigInteger(token);
                    return new java.math.BigDecimal(token);
                }
                catch (NumberFormatException e) { throw bad(); }
            }
            try {
                long n = Long.parseLong(s.substring(start, at));
                if (c == '-' || n > MAX_INTEGER) throw new IllegalArgumentException("unsafe_integer");
                return n;
            }
            catch (NumberFormatException e) { throw bad(); }
        }
        String string() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') {
                    String value = b.toString();
                    // Also verifies Unicode pairs in decoded strings.
                    StringBuilder ignored = new StringBuilder(); quote(ignored, value);
                    return value;
                }
                if (c < 32) throw bad();
                if (c != '\\') { b.append(c); continue; }
                char escaped = take();
                switch (escaped) {
                    case '"': case '\\': case '/': b.append(escaped); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (at + 4 > s.length()) throw bad();
                        int n = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = take();
                            int h = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (h < 0) throw bad();
                            n = n * 16 + h;
                        }
                        b.append((char) n); break;
                    default: throw bad();
                }
            }
        }
    }
}
