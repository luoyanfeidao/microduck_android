package com.microduck.sim;

import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns an immutable native JSON string and exclusively transferred captured metadata. */
final class CapturedReport {
    private final String nativeJson;
    private final JSONObject fields;
    CapturedReport(String nativeJson,JSONObject fields) {
        this.nativeJson=nativeJson; this.fields=fields;
        // Caller must never mutate fields or its nested values after transfer.
    }
    JSONObject materialize() throws JSONException {
        // Called by the report thread. Native/ORT/renderer objects are never consulted here.
        JSONObject data=new JSONObject(nativeJson);
        for(Iterator<String> keys=fields.keys();keys.hasNext();) {
            String key=keys.next(); data.put(key,fields.get(key));
        }
        return data;
    }
}
