package com.microduck.sim;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only loopback checks of the actual Android server/command/lease classes. */
public final class NetworkControlCheck {
    public static void main(String[] args) throws Exception {
        RemoteCommand walk=RemoteCommand.parse("{\"op\":\"walk\",\"vx\":0.3,\"vy\":-0.15,\"yaw\":0.5}");
        if(walk.vx!=.3f || walk.vy!=-.15f || walk.yaw!=.5f) throw new AssertionError("Velocity mapping");
        if(!RemoteCommand.parse("{\"op\":\"roulade\"}").op.equals("roulade")) throw new AssertionError("Roll command");
        for(String text:new String[]{"{}","{\"op\":7}","{\"op\":\"unknown\"}",
            "{\"op\":\"roulade\",\"vx\":0}","{\"op\":\"stand\",\"vx\":0}","{\"op\":\"walk\",\"vx\":0.31}",
            "{\"op\":\"walk\",\"vy\":0.16}","{\"op\":\"walk\",\"yaw\":0.51}",
            "{\"op\":\"walk\",\"vx\":\"0.3\"}","{\"op\":\"walk\",\"vx\":null}",
            "{\"op\":\"reset\",\"pose\":\"HOME\"}","{\"op\":\"reset\",\"pose\":true}"}) reject(text);
        RemoteLease lease=new RemoteLease(); lease.claim(0);
        if(lease.expired(2_999_999_999L) || !lease.expired(3_000_000_000L)) throw new AssertionError("Lease timeout");
        lease.heartbeat(2_000_000_000L); if(lease.expired(4_999_999_999L)) throw new AssertionError("Heartbeat not extending lease");
        lease.release(); lease.heartbeat(5_000_000_000L); if(lease.active() || lease.expired(9_000_000_000L)) throw new AssertionError("Local ownership");
        mailboxCheck(walk);
        AtomicInteger commands=new AtomicInteger(); byte[] page="<h1>Microduck 🦆</h1>".getBytes(StandardCharsets.UTF_8);
        VideoFrames video=new VideoFrames(); video.available(true); AtomicInteger frameRequests=new AtomicInteger();
        try(LanControlServer server=new LanControlServer(0,page,()->"{\"running\":false}",cmd->{
            if(cmd.op.equals("reset")) throw new LanControlServer.CommandFailure(409,"Local test running");
            commands.incrementAndGet(); return "{\"ok\":true}";
        },()->{frameRequests.incrementAndGet();return video.request(System.nanoTime());})) {
            String origin="http://127.0.0.1:"+server.port(),auth="Bearer "+server.token();
            check(request(origin,"/",null,null),200,"Microduck");
            check(request(origin,"/health",null,null),200,"microduck-lan");
            check(request(origin,"/api/state",null,null),401,"error");
            check(request(origin,"/api/state","Bearer wrong",null),401,"error");
            check(request(origin,"/api/state",auth,null),200,"running");
            check(request(origin,"/api/frame",null,null),401,"error");
            if(frameRequests.get()!=0) throw new AssertionError("Unauthenticated request started video");
            check(request(origin,"/api/frame",auth,null),503,"Waiting");
            if(!video.begin(System.nanoTime())) throw new AssertionError("Frame request did not activate capture");
            byte[] jpeg={(byte)255,(byte)216,42,(byte)255,(byte)217};
            video.publish(jpeg,640,480,System.nanoTime(),1,2);
            frameCheck(origin,auth,jpeg);
            check(request(origin,"/api/frame",auth,"{}"),405,"error");
            check(request(origin,"/api/command",auth,"{\"op\":\"walk\",\"vx\":0.3}"),200,"ok");
            check(request(origin,"/api/command",auth,"{\"op\":\"stand\"}"),200,"ok");
            check(request(origin,"/api/command",auth,"{\"op\":\"roulade\"}"),200,"ok");
            check(request(origin,"/api/command",auth,"{\"op\":\"pause\"}"),200,"ok");
            check(request(origin,"/api/command",auth,"{\"op\":\"walk\",\"vx\":100}"),400,"error");
            check(request(origin,"/api/command",auth,"{\"op\":\"reset\",\"pose\":\"SIT\"}"),409,"error");
            check(request(origin,"/api/command",auth,null),405,"error");
            check(request(origin,"/no-such-path",auth,null),404,"error");
            String prefix="POST /api/command HTTP/1.1\r\nAuthorization: "+auth+"\r\nContent-Type: application/json\r\n";
            checkRaw(server,prefix+"Content-Length: 9999\r\n\r\n",413);
            checkRaw(server,prefix+"Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",400);
            checkRaw(server,prefix+"Transfer-Encoding: chunked\r\n\r\n",400);
            checkRaw(server,prefix+"\r\n",411);
            checkRaw(server,"GET /health HTTP/1.1\r\nX-Long: "+"x".repeat(5000)+"\r\n\r\n",431);
            if(commands.get()!=4) throw new AssertionError("Rejected request executed command");
            ExecutorService concurrent=Executors.newFixedThreadPool(2);
            try {
                Future<?> pictures=concurrent.submit(()->{
                    try { for(int i=0;i<8;i++) frameCheck(origin,auth,jpeg); }
                    catch(Exception e) { throw new IllegalStateException(e); }
                });
                Future<?> heartbeats=concurrent.submit(()->{
                    try { for(int i=0;i<8;i++) check(request(origin,"/api/command",auth,"{\"op\":\"heartbeat\"}"),200,"ok"); }
                    catch(Exception e) { throw new IllegalStateException(e); }
                });
                pictures.get(2,TimeUnit.SECONDS); heartbeats.get(2,TimeUnit.SECONDS);
            } finally { concurrent.shutdownNow(); }
            video.close(); check(request(origin,"/api/frame",auth,null),503,"Waiting");
            try(Socket slow=new Socket(InetAddress.getLoopbackAddress(),server.port())) {
                slow.setSoTimeout(3500); slow.getOutputStream().write('G');
                if(slow.getInputStream().read()!=-1) throw new AssertionError("Slow header not closed");
            }
        }
        System.out.println("{\"http_page_state_commands\":true,\"token_required\":true,\"invalid_requests_never_apply\":true,"
            +"\"http_size_duplicate_transfer_and_timeout_checks\":true,\"velocity_pose_validation\":true,"
            +"\"heartbeat_and_local_takeover\":true,\"mailbox_timeout_cancels_queued_command\":true,\"mailbox_pending_limit\":true,"
            +"\"jpeg_auth_bytes_headers_and_unavailable\":true,\"concurrent_frames_and_heartbeats\":true}");
    }
    private static void frameCheck(String origin,String auth,byte[] expected) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(origin+"/api/frame").openConnection();
        try {
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000); connection.setRequestProperty("Authorization",auth);
            if(connection.getResponseCode()!=200 || !"image/jpeg".equals(connection.getContentType())
                || !"1".equals(connection.getHeaderField("X-Frame-Sequence"))
                || connection.getHeaderField("X-Frame-Age-Ms")==null
                || !connection.getHeaderField("Content-Security-Policy").contains("img-src 'self' blob:"))
                throw new AssertionError("JPEG response headers");
            try(InputStream input=connection.getInputStream()) {
                if(!java.util.Arrays.equals(input.readAllBytes(),expected)) throw new AssertionError("JPEG bytes changed");
            }
        } finally { connection.disconnect(); }
    }
    private static void mailboxCheck(RemoteCommand walk) throws Exception {
        AtomicReference<Runnable> queued=new AtomicReference<>(); AtomicInteger applied=new AtomicInteger();
        CountDownLatch accepted=new CountDownLatch(1); RemoteMailbox mailbox=new RemoteMailbox(task->{queued.set(task);accepted.countDown();});
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> status=caller.submit(()->{
                try { mailbox.submit(walk,c->{applied.incrementAndGet();return "ok";},100); return 200; }
                catch(LanControlServer.CommandFailure e) { return e.status; }
            });
            if(!accepted.await(2,TimeUnit.SECONDS)) throw new AssertionError("Mailbox submit blocked");
            try { mailbox.submit(walk,c->"unexpected",100); throw new AssertionError("Unbounded pending command"); }
            catch(LanControlServer.CommandFailure e) { if(e.status!=429) throw e; }
            if(status.get(2,TimeUnit.SECONDS)!=504) throw new AssertionError("Missing command timeout");
            queued.get().run(); if(applied.get()!=0) throw new AssertionError("Timed-out queued command applied");
        } finally { caller.shutdownNow(); }
        RemoteMailbox direct=new RemoteMailbox(Runnable::run);
        if(!direct.submit(walk,c->"applied").equals("applied")) throw new AssertionError("Successful acknowledgement");
    }
    private static void reject(String text) throws Exception {
        try { RemoteCommand.parse(text); } catch(Exception expected) { return; }
        throw new AssertionError("Invalid command accepted");
    }
    private static String[] request(String origin,String path,String auth,String body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(origin+path).openConnection();
        try {
            connection.setConnectTimeout(2000); connection.setReadTimeout(3000);
            if(auth!=null) connection.setRequestProperty("Authorization",auth);
            if(body!=null) {connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
                try(java.io.OutputStream out=connection.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
            int code=connection.getResponseCode();
            try(InputStream in=code<400?connection.getInputStream():connection.getErrorStream()) {
                return new String[]{Integer.toString(code),new String(in.readAllBytes(),StandardCharsets.UTF_8)};
            }
        } finally { connection.disconnect(); }
    }
    private static void check(String[] reply,int code,String content) {
        if(!reply[0].equals(Integer.toString(code)) || !reply[1].contains(content)) throw new AssertionError("Unexpected HTTP result "+reply[0]);
    }
    private static void checkRaw(LanControlServer server,String request,int code) throws Exception {
        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),server.port())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream data=new ByteArrayOutputStream(); String headers="";
            while(!headers.endsWith("\r\n\r\n")) {
                int value=socket.getInputStream().read(); if(value<0 || data.size()>8192) throw new AssertionError("Incomplete HTTP headers");
                data.write(value); headers=data.toString("US-ASCII");
            }
            if(!headers.startsWith("HTTP/1.1 "+code+" ")) throw new AssertionError("Expected HTTP "+code);
            int length=-1;
            for(String row:headers.split("\r\n")) if(row.startsWith("Content-Length: ")) length=Integer.parseInt(row.substring(16));
            // A declared-length HTTP response completes at that length, not at TCP EOF.
            if(length<1 || length>1024) throw new AssertionError("Invalid error response length");
            byte[] body=socket.getInputStream().readNBytes(length);
            if(body.length!=length || !new String(body,StandardCharsets.UTF_8).contains("error"))
                throw new AssertionError("Incomplete HTTP error body");
        }
    }
}
