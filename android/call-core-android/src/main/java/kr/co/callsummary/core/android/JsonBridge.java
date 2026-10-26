package kr.co.callsummary.core.android;

import org.json.JSONArray;
import org.json.JSONObject;
import kr.co.callsummary.core.CoreException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonBridge {
    private JsonBridge(){}
    public static Map<String,Object> parseObject(String json){
        if(json==null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new CoreException("JSON_TOO_LARGE");
        try{return object(new JSONObject(json));}catch(Exception e){throw new CoreException("INVALID_JSON",e);}
    }
    private static Map<String,Object> object(JSONObject json)throws Exception{
        Map<String,Object> out=new LinkedHashMap<>();Iterator<String> keys=json.keys();
        while(keys.hasNext()){String key=keys.next();out.put(key,value(json.get(key)));}return out;
    }
    private static Object value(Object value)throws Exception{
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONObject)return object((JSONObject)value);
        if(value instanceof JSONArray){List<Object> out=new ArrayList<>();JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)out.add(value(array.get(i)));return out;}
        return value;
    }
}
