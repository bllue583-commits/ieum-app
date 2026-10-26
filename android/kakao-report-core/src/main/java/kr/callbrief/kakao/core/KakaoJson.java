package kr.callbrief.kakao.core;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small, bounded wire codec. Rejects duplicate keys, malformed UTF-8 and trailing data. */
public final class KakaoJson {
    private KakaoJson() {}
    public static Map<String, Object> object(byte[] bytes) {
        if (bytes.length > 16_384) throw new IllegalArgumentException("response_too_large");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Parser p = new Parser(text);
            Object value = p.value(0); p.ws();
            if (p.at != text.length() || !(value instanceof Map)) throw new IllegalArgumentException("invalid_response");
            @SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>) value;
            return result;
        } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("invalid_utf8"); }
    }
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String)value);
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) return value.toString();
        if (value instanceof Map) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) {
                if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("invalid_key");
                j.add(quote((String)e.getKey()) + ":" + encode(e.getValue()));
            }
            return j.toString();
        }
        if (value instanceof Collection) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object v : (Collection<?>)value) j.add(encode(v));
            return j.toString();
        }
        throw new IllegalArgumentException("unsupported_json");
    }
    private static String quote(String s) {
        validateUnicode(s);
        StringBuilder b = new StringBuilder("\"");
        for (int i=0; i<s.length(); i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c<32) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    public static void validateUnicode(String s) {
        Objects.requireNonNull(s);
        for (int i=0; i<s.length(); i++) {
            char c=s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=s.length() || !Character.isLowSurrogate(s.charAt(i))) throw new IllegalArgumentException("invalid_unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid_unicode");
        }
    }
    private static final class Parser {
        final String s; int at, items;
        Parser(String s) { this.s=s; }
        RuntimeException bad() { return new IllegalArgumentException("invalid_json"); }
        void ws() { while(at<s.length() && " \r\n\t".indexOf(s.charAt(at))>=0) at++; }
        char take() { if(at>=s.length()) throw bad(); return s.charAt(at++); }
        void expect(char c) { if(take()!=c) throw bad(); }
        Object value(int depth) {
            if(depth>12 || ++items>512) throw bad();
            ws(); if(at==s.length()) throw bad(); char c=s.charAt(at);
            if(c=='"') return string();
            if(c=='{') {
                at++; ws(); Map<String,Object> m=new LinkedHashMap<>();
                if(at<s.length() && s.charAt(at)=='}') { at++; return m; }
                while(true) {
                    ws(); String key=string(); ws(); expect(':'); Object v=value(depth+1);
                    if(m.containsKey(key)) throw bad(); m.put(key,v); ws(); char end=take();
                    if(end=='}') return m; if(end!=',') throw bad();
                }
            }
            if(c=='[') {
                at++; ws(); List<Object> list=new ArrayList<>();
                if(at<s.length() && s.charAt(at)==']') { at++; return list; }
                while(true) { list.add(value(depth+1)); ws(); char end=take(); if(end==']') return list; if(end!=',') throw bad(); }
            }
            for(String literal : new String[]{"true","false","null"}) if(s.startsWith(literal,at)) {
                at+=literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
            int start=at; if(c=='-') at++;
            if(at>=s.length()) throw bad();
            if(s.charAt(at)=='0') at++;
            else { if(s.charAt(at)<'1' || s.charAt(at)>'9') throw bad(); while(at<s.length() && digit(s.charAt(at))) at++; }
            boolean integer=true;
            if(at<s.length() && s.charAt(at)=='.') {
                integer=false; at++; int n=at; while(at<s.length() && digit(s.charAt(at))) at++; if(n==at) throw bad();
            }
            if(at<s.length() && (s.charAt(at)=='e'||s.charAt(at)=='E')) {
                integer=false; at++; if(at<s.length() && (s.charAt(at)=='+'||s.charAt(at)=='-')) at++;
                int n=at; while(at<s.length() && digit(s.charAt(at))) at++; if(n==at) throw bad();
            }
            String n=s.substring(start,at); if(n.length()>64) throw bad();
            try { if(integer) return Long.valueOf(n); return new BigDecimal(n); } catch(NumberFormatException e) { throw bad(); }
        }
        boolean digit(char c) { return c>='0' && c<='9'; }
        String string() {
            expect('"'); StringBuilder b=new StringBuilder();
            while(true) {
                char c=take(); if(c=='"') { String result=b.toString(); validateUnicode(result); return result; }
                if(c<32) throw bad();
                if(c!='\\') { b.append(c); continue; }
                c=take(); switch(c) {
                    case '"': case '\\': case '/': b.append(c); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        int code=0; for(int i=0;i<4;i++) { int d=Character.digit(take(),16); if(d<0) throw bad(); code=code*16+d; }
                        b.append((char)code); break;
                    default: throw bad();
                }
            }
        }
    }
}
