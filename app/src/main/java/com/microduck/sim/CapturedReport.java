package com.microduck.sim;

import org.json.JSONObject;

/** Immutable native JSON and callbacks that close over captured scalar values only. */
final class CapturedReport {
    interface Fields { void putInto(JSONObject data) throws Exception; }
    private final String nativeJson;
    private final Fields fields;
    CapturedReport(String nativeJson,Fields fields) {
        this.nativeJson=nativeJson; this.fields=fields;
    }
    JSONObject materialize() throws Exception {
        // Called by the report thread. Native/ORT/renderer objects are never consulted here.
        JSONObject data=new JSONObject(nativeJson);
        fields.putInto(data);
        return data;
    }
}
