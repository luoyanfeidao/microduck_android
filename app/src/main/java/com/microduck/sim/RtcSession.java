package com.microduck.sim;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Bounded signaling mailbox. All native callbacks must match the current generation. */
final class RtcSession {
    static final long LEASE_NS=8_000_000_000L;
    static final int MAX_CANDIDATES=128,MAX_SDP=24*1024;
    private String id="",answer="",error="",connection="idle";
    private final List<String> candidates=new ArrayList<>();
    private final java.util.LinkedHashMap<String,Long> canceled=new java.util.LinkedHashMap<>();
    private long generation,touched;
    private boolean closed;
    synchronized long open(String id,long now) {
        canceled.entrySet().removeIf(entry->now-entry.getValue()>30_000_000_000L);
        if(closed || canceled.containsKey(id)) return -1;
        this.id=id; answer=error=""; connection="negotiating"; candidates.clear(); touched=now;
        return ++generation;
    }
    synchronized boolean current(long value) { return !closed && !id.isEmpty() && generation==value; }
    synchronized boolean touch(String id,long now) {
        if(closed || this.id.isEmpty() || !this.id.equals(id)) return false;
        touched=now; return true;
    }
    synchronized long end(String id,long now) {
        // A canceled offer can arrive after close on another HTTP connection.
        canceled.put(id,now);
        if(canceled.size()>64) canceled.remove(canceled.keySet().iterator().next());
        if(!this.id.equals(id) || this.id.isEmpty()) return -1;
        this.id=""; connection="closed"; candidates.clear(); answer=""; return ++generation;
    }
    synchronized long expire(long now) { return active() && now-touched>LEASE_NS?end(id,now):-1; }
    synchronized boolean active() { return !closed && !id.isEmpty(); }
    synchronized long generation() { return generation; }
    synchronized void answer(long value,String sdp) {
        if(current(value)) { if(sdp.length()>MAX_SDP) failed(value,"Answer too large"); else answer=sdp; }
    }
    synchronized void candidate(long value,String json) {
        if(current(value)) {
            if(candidates.size()==MAX_CANDIDATES) failed(value,"Too many ICE candidates");
            else candidates.add(json);
        }
    }
    synchronized void failed(long value,String reason) { if(current(value)) { error=reason; connection="failed"; } }
    synchronized void connection(long value,String state) { if(current(value) && error.isEmpty()) connection=state; }
    synchronized String connection() { return connection; }
    synchronized String error() { return error; }
    synchronized String poll(String id,int after,long now) throws Exception {
        if(!touch(id,now)) throw new LanControlServer.CommandFailure(409,"Video session ended");
        if(after<0 || after>candidates.size()) throw new IllegalArgumentException("Invalid ICE cursor");
        JSONArray ice=new JSONArray();
        for(int i=after;i<candidates.size();i++) ice.put(new JSONObject(candidates.get(i)));
        JSONObject data=new JSONObject().put("ok",true).put("state",connection).put("error",error)
            .put("candidates",ice).put("next",candidates.size());
        if(!answer.isEmpty()) data.put("answer",new JSONObject().put("type","answer").put("sdp",answer));
        return data.toString();
    }
    synchronized void close() { closed=true; canceled.clear(); id=""; answer=""; candidates.clear(); ++generation; connection="closed"; }
}
