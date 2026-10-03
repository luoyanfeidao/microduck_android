package com.microduck.sim;

import org.json.JSONObject;

/** Immutable native JSON and callbacks that close over captured scalar values only. */
final class CapturedReport implements Runnable {
    interface Fields { void putInto(JSONObject data) throws Exception; }
    interface Writer { void write(JSONObject data) throws Exception; }
    private final String nativeJson;
    private final Fields fields;
    private final Writer writer;
    CapturedReport(String nativeJson,Fields fields) {
        this(nativeJson,fields,null);
    }
    CapturedReport(String nativeJson,Fields fields,Writer writer) {
        this.nativeJson=nativeJson; this.fields=fields; this.writer=writer;
    }
    JSONObject materialize() throws Exception {
        // Called by the report thread. Native/ORT/renderer objects are never consulted here.
        JSONObject data=new JSONObject(nativeJson);
        fields.putInto(data);
        return data;
    }
    @Override public void run() {
        try {
            if(writer==null) throw new IllegalStateException("No report writer");
            writer.write(materialize());
        } catch(Exception e) { throw new IllegalStateException("Cannot write captured report",e); }
    }
}
