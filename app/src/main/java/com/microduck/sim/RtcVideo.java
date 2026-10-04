package com.microduck.sim;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.webrtc.*;

/** A single send-only LAN peer. Signaling/native lifetime never blocks simulation or camera GL. */
final class RtcVideo {
    private static boolean initialized;
    private final HandlerThread thread=new HandlerThread("microduck-webrtc");
    private final Handler owner;
    private final Object frameLock=new Object();
    private final RtcSession session=new RtcSession();
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoSource source;
    private VideoTrack track;
    private long peerGeneration;
    private volatile CapturerObserver capturer;
    private volatile boolean closed,ready;
    private volatile String problem="",hardwareCodecs="",selectedCodec="",encoder="";
    private volatile long submitted,framesEncoded,bytesSent;
    private final java.util.concurrent.atomic.AtomicBoolean pendingOpen=new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable maintenance=this::maintain;
    RtcVideo(Context context,EglBase.Context egl) {
        thread.start(); owner=new Handler(thread.getLooper());
        owner.post(()->{
            if(closed) return;
            try {
                synchronized(RtcVideo.class) {
                    if(!initialized) {
                        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(false).createInitializationOptions()); initialized=true;
                    }
                }
                // Hardware-only: unsupported codecs fail visibly, rather than silently burdening CPU simulation.
                HardwareVideoEncoderFactory hardware=new HardwareVideoEncoderFactory(egl,true,false);
                StringBuilder codecs=new StringBuilder();
                for(VideoCodecInfo codec:hardware.getSupportedCodecs()) if(codecs.indexOf(codec.name)<0) codecs.append(codec.name).append(' ');
                hardwareCodecs=codecs.toString().trim();
                factory=PeerConnectionFactory.builder().setVideoEncoderFactory(hardware)
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl)).createPeerConnectionFactory();
                source=factory.createVideoSource(false); source.adaptOutputFormat(HeadCamera.WIDTH,HeadCamera.HEIGHT,VideoCapture.RTC_FPS);
                capturer=source.getCapturerObserver(); capturer.onCapturerStarted(true);
                track=factory.createVideoTrack("head_camera",source); ready=true;
            } catch(RuntimeException | LinkageError e) { problem="WebRTC initialization failed: "+e.getClass().getSimpleName(); }
            owner.postDelayed(maintenance,1000);
        });
    }
    boolean wantsFrames() { return ready && !closed && session.active() && !session.connection().equals("failed"); }
    void frame(VideoFrame frame) {
        synchronized(frameLock) {
            CapturerObserver sink=capturer;
            if(wantsFrames() && sink!=null) {
                try { sink.onFrameCaptured(frame); submitted++; }
                catch(RuntimeException | LinkageError e) { session.failed(session.generation(),"Video frame delivery failed"); }
            }
        }
    }
    void cameraFailed() { session.failed(session.generation(),"Camera Surface output failed; select JPEG"); }
    String signal(String body) throws Exception {
        JSONObject data=new JSONObject(body); String op=data.getString("op"),id=data.getString("id");
        if(!id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid video session id");
        if(closed) throw new LanControlServer.CommandFailure(503,"Video closed");
        switch(op) {
            case "open": {
                if(!ready) throw new LanControlServer.CommandFailure(503,"WebRTC not ready");
                String sdp=data.getString("sdp");
                if(sdp.length()<10 || sdp.length()>RtcSession.MAX_SDP || !sdp.startsWith("v=0"))
                    throw new IllegalArgumentException("Invalid offer");
                if(!pendingOpen.compareAndSet(false,true)) throw new LanControlServer.CommandFailure(429,"Video opening; retry");
                long generation=session.open(id,System.nanoTime());
                if(generation<0) { pendingOpen.set(false); throw new LanControlServer.CommandFailure(409,"Video canceled"); }
                if(!owner.post(()->{ try { open(generation,sdp); } finally { pendingOpen.set(false); } })) {
                    pendingOpen.set(false); throw new LanControlServer.CommandFailure(503,"Video owner stopped");
                }
                return "{\"ok\":true}";
            }
            case "poll": return session.poll(id,data.getInt("after"),System.nanoTime());
            case "close": {
                long generation=session.end(id,System.nanoTime());
                if(generation>=0) owner.post(()->{ if(session.generation()==generation) disposePeer(); });
                return "{\"ok\":true}";
            }
            default: throw new IllegalArgumentException("Invalid signaling operation");
        }
    }
    private void open(long generation,String sdp) {
        if(!session.current(generation)) return;
        try {
            disposePeer(); peerGeneration=generation; selectedCodec=encoder=""; framesEncoded=bytesSent=0;
            PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.emptyList());
            config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer=factory.createPeerConnection(config,new PeerObserver(generation));
            if(peer==null) throw new IllegalStateException("Peer creation failed");
            peer.setAudioPlayout(false); peer.setAudioRecording(false);
            peer.addTrack(track,Collections.singletonList("microduck-head"));
            PeerConnection target=peer;
            target.setRemoteDescription(new DescriptionObserver(generation,()->{
                // Prefer H.264 from the hardware factory; keep offered hardware alternatives.
                List<RtpCapabilities.CodecCapability> codecs=factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs;
                codecs.sort((a,b)->Boolean.compare(!a.name.equalsIgnoreCase("H264"),!b.name.equalsIgnoreCase("H264")));
                for(RtpTransceiver transceiver:target.getTransceivers())
                    if(transceiver.getMediaType()==MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) transceiver.setCodecPreferences(codecs);
                target.createAnswer(new DescriptionObserver(generation,null) {
                    @Override public void onCreateSuccess(SessionDescription answer) {
                        owner.post(()->{ if(session.current(generation)) target.setLocalDescription(
                            new DescriptionObserver(generation,()->{
                                session.answer(generation,target.getLocalDescription().description);
                                for(RtpSender sender:target.getSenders()) {
                                    RtpParameters parameters=sender.getParameters();
                                    for(RtpParameters.Encoding encoding:parameters.encodings) {
                                        encoding.maxBitrateBps=1_500_000; encoding.maxFramerate=VideoCapture.RTC_FPS;
                                    }
                                    sender.setParameters(parameters);
                                }
                            }),answer); });
                    }
                },new MediaConstraints());
            }),new SessionDescription(SessionDescription.Type.OFFER,sdp));
        } catch(RuntimeException e) { session.failed(generation,"WebRTC offer failed"); disposePeer(); }
    }
    private class DescriptionObserver implements SdpObserver {
        final long generation; final Runnable success;
        DescriptionObserver(long generation,Runnable success) { this.generation=generation; this.success=success; }
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {
            owner.post(()->{ if(session.current(generation) && success!=null) {
                try { success.run(); } catch(RuntimeException e) { session.failed(generation,"WebRTC negotiation failed"); }
            } });
        }
        @Override public void onCreateFailure(String error) { session.failed(generation,"WebRTC answer failed"); }
        @Override public void onSetFailure(String error) { session.failed(generation,"WebRTC description rejected"); }
    }
    private final class PeerObserver implements PeerConnection.Observer {
        private final long generation;
        PeerObserver(long generation) { this.generation=generation; }
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) { session.connection(generation,state.name().toLowerCase(java.util.Locale.ROOT)); }
        @Override public void onIceCandidate(IceCandidate candidate) {
            try { session.candidate(generation,new JSONObject().put("candidate",candidate.sdp)
                .put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex).toString()); }
            catch(Exception e) { session.failed(generation,"ICE serialization failed"); }
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel channel) { channel.dispose(); }
        @Override public void onRenegotiationNeeded() {}
    }
    private void maintain() {
        if(closed) return;
        if(session.expire(System.nanoTime())>=0) disposePeer();
        if(peer!=null) {
            long generation=peerGeneration;
            peer.getStats(report->{
                if(!session.current(generation)) return;
                for(RTCStats stats:report.getStatsMap().values()) {
                    Map<String,Object> values=stats.getMembers();
                    if(!stats.getType().equals("outbound-rtp") || !"video".equals(values.get("kind"))) continue;
                    Object codecId=values.get("codecId"); RTCStats codec=report.getStatsMap().get(codecId);
                    selectedCodec=codec==null?"":String.valueOf(codec.getMembers().getOrDefault("mimeType",""));
                    encoder=String.valueOf(values.getOrDefault("encoderImplementation",""));
                    framesEncoded=number(values.get("framesEncoded")); bytesSent=number(values.get("bytesSent"));
                }
            });
        }
        owner.postDelayed(maintenance,1000);
    }
    private static long number(Object value) { return value instanceof Number?((Number)value).longValue():0; }
    private void disposePeer() { if(peer!=null) { peer.close(); peer.dispose(); peer=null; } }
    CapturedReport.Fields captureReport() {
        boolean initialized=ready,active=session.active(); String state=session.connection(),error=problem.isEmpty()?session.error():problem;
        String codecs=hardwareCodecs,codec=selectedCodec,implementation=encoder; long frames=submitted,encoded=framesEncoded,bytes=bytesSent;
        return data->{
            data.put("rtc_ready",initialized); data.put("rtc_active",active); data.put("rtc_connection",state);
            data.put("rtc_hardware_codecs",codecs); data.put("rtc_encoder_factory","HardwareVideoEncoderFactory");
            data.put("rtc_codec",codec); data.put("rtc_encoder_implementation",implementation);
            data.put("rtc_frames_submitted",frames); data.put("rtc_frames_encoded",encoded); data.put("rtc_bytes_sent",bytes);
            data.put("rtc_error",error); data.put("rtc_max_viewers",1); data.put("rtc_ice_servers",0);
        };
    }
    void close(Runnable completion) {
        synchronized(frameLock) { closed=true; }
        session.close();
        owner.post(()->{
            try {
                owner.removeCallbacks(maintenance); disposePeer();
                capturer=null;
                if(source!=null) source.getCapturerObserver().onCapturerStopped();
                if(track!=null) { track.dispose(); track=null; }
                if(source!=null) { source.dispose(); source=null; }
                if(factory!=null) { factory.dispose(); factory=null; }
            } finally { ready=false; completion.run(); thread.quitSafely(); }
        });
    }
}
