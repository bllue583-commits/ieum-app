package kr.co.callsummary.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Matches Python sorted-key, ensure_ascii=false, compact JSON for integral values. */
public final class JsonCanonical {
    private JsonCanonical() {}
    public static String encode(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value); return out.toString();
    }
    public static String digest(Object value) { return sha256(encode(value).getBytes(StandardCharsets.UTF_8)); }
    public static String textHash(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new CoreException("SHA256_UNAVAILABLE", error); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return out.toString();
    }
    public static void validHash(Object value) {
        if(!(value instanceof String) || !((String)value).matches("[0-9a-f]{64}")) throw new CoreException("INVALID_HASH");
    }
    @SuppressWarnings("unchecked")
    private static void append(StringBuilder out,Object value) {
        if(value==null) out.append("null");
        else if(value instanceof String) quote(out,(String)value);
        else if(value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) out.append(value);
        else if(value instanceof List) {
            out.append('['); boolean first=true;
            for(Object item:(List<?>)value) { if(!first)out.append(',');first=false;append(out,item); } out.append(']');
        } else if(value instanceof Map) {
            Map<String,Object> map=(Map<String,Object>)value;
            List<String> keys=new ArrayList<>(map.keySet());Collections.sort(keys);
            out.append('{');boolean first=true;
            for(String key:keys) { if(!first)out.append(',');first=false;quote(out,key);out.append(':');append(out,map.get(key)); }out.append('}');
        } else throw new CoreException("UNSUPPORTED_CANONICAL_VALUE");
    }
    private static void quote(StringBuilder out,String text) {
        out.append('"');
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"' || c=='\\')out.append('\\').append(c);
            else if(c=='\b')out.append("\\b");else if(c=='\f')out.append("\\f");
            else if(c=='\n')out.append("\\n");else if(c=='\r')out.append("\\r");else if(c=='\t')out.append("\\t");
            else if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
            else if(Character.isHighSurrogate(c)) {
                if(i+1>=text.length() || !Character.isLowSurrogate(text.charAt(i+1)))throw new CoreException("INVALID_UNICODE");
                out.append(c).append(text.charAt(++i));
            } else if(Character.isLowSurrogate(c))throw new CoreException("INVALID_UNICODE");
            else out.append(c);
        }
        out.append('"');
    }
}
