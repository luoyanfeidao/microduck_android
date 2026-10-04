package com.microduck.sim;

import org.json.JSONObject;

/** Session replacement, callback isolation, lease expiry and HTTP reordering without native WebRTC. */
public final class RtcSessionCheck {
    public static void main(String[] args) throws Exception {
        String a="a".repeat(32),b="b".repeat(32),c="c".repeat(32);
        RtcSession session=new RtcSession(); long first=session.open(a,0);
        session.answer(first,"v=0\r\nanswer"); session.candidate(first,"{\"candidate\":\"one\"}");
        JSONObject reply=new JSONObject(session.poll(a,0,1));
        if(!reply.has("answer") || reply.getJSONArray("candidates").length()!=1 || reply.getInt("next")!=1)
            throw new AssertionError("Answer/candidate mailbox");
        if(new JSONObject(session.poll(a,1,2)).getJSONArray("candidates").length()!=0) throw new AssertionError("Duplicate ICE cursor");
        long second=session.open(b,3); session.answer(first,"late old answer"); session.candidate(first,"{\"candidate\":\"late\"}");
        session.connection(first,"connected"); session.failed(first,"late failure");
        if(session.end(a,4)>=0 || !session.current(second)) throw new AssertionError("Old close affected replacement");
        reply=new JSONObject(session.poll(b,0,5));
        if(reply.has("answer") || reply.getJSONArray("candidates").length()!=0 || !reply.getString("error").isEmpty()
            || !reply.getString("state").equals("negotiating")) throw new AssertionError("Stale callback affected new peer");
        try { session.poll(a,0,6); throw new AssertionError("Stale poll renewed lease"); }
        catch(LanControlServer.CommandFailure expected) { if(expected.status!=409) throw expected; }
        if(session.expire(5+RtcSession.LEASE_NS)!=-1 || session.expire(6+RtcSession.LEASE_NS)<0)
            throw new AssertionError("Viewer lease boundary");
        if(session.current(second) || session.active()) throw new AssertionError("Expired peer still active");
        session.end(c,10+RtcSession.LEASE_NS);
        if(session.open(c,11+RtcSession.LEASE_NS)>=0) throw new AssertionError("Late offer revived canceled viewer");
        long third=session.open("d".repeat(32),12+RtcSession.LEASE_NS);
        for(int i=0;i<=RtcSession.MAX_CANDIDATES;i++) session.candidate(third,"{\"candidate\":\"x\"}");
        reply=new JSONObject(session.poll("d".repeat(32),0,13+RtcSession.LEASE_NS));
        if(reply.getJSONArray("candidates").length()!=RtcSession.MAX_CANDIDATES || reply.getString("error").isEmpty())
            throw new AssertionError("Unbounded ICE mailbox");
        session.close(); if(session.open(a,100)>=0 || session.current(third)) throw new AssertionError("Closed session accepted work");
        System.out.println("{\"answer_ice_cursor\":true,\"replacement_ignores_old_callbacks_close\":true,"
            +"\"lease_expires\":true,\"close_before_offer_cannot_revive\":true,\"ice_mailbox_bounded\":true,\"shutdown_fences_callbacks\":true}");
    }
}
