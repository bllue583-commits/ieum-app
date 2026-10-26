package kr.co.callsummary.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** App-owned claim snapshot. It is not a replacement for the worker v1 JSON. */
public final class AnalysisBinding {
    public final Map<String,Object> values;
    AnalysisBinding(Map<String,Object> values) { this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    public String text(String key) { Object v=values.get(key);return v==null?null:(String)v; }
    public long number(String key) { return ((Number)values.get(key)).longValue(); }
}
