package com.microduck.sim;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/** Small HTTP/1.1 LAN server; bounded requests, no native handles or policy sessions. */
final class LanControlServer implements AutoCloseable {
    interface Commands { String apply(RemoteCommand command) throws Exception; }
    static final class CommandFailure extends Exception {
        private static final long serialVersionUID=1;
        final int status;
        CommandFailure(int status,String message) { super(message); this.status=status; }
    }
    private static final class BadRequest extends Exception {
        private static final long serialVersionUID=1;
        final int status;
        BadRequest(int status) { this.status=status; }
    }
    private final ServerSocket listener=new ServerSocket();
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4));
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1);
    private final byte[] page;
    private final Supplier<String> state;
    private final Commands commands;
    private final Supplier<VideoFrames.Frame> video;
    private final String token;
    private volatile boolean closed;
    LanControlServer(int port,byte[] page,Supplier<String> state,Commands commands) throws IOException {
        this(port,page,state,commands,()->null);
    }
    LanControlServer(int port,byte[] page,Supplier<String> state,Commands commands,Supplier<VideoFrames.Frame> video) throws IOException {
        this.page=page.clone(); this.state=state; this.commands=commands; this.video=video;
        deadlines.setRemoveOnCancelPolicy(true);
        byte[] random=new byte[16]; new SecureRandom().nextBytes(random);
        StringBuilder key=new StringBuilder();
        for(byte value:random) key.append(String.format(Locale.ROOT,"%02x",value&255));
        token=key.toString();
        try { listener.setReuseAddress(true); listener.bind(new InetSocketAddress(port),6); }
        catch(IOException e) { clients.shutdownNow(); deadlines.shutdownNow(); listener.close(); throw e; }
        Thread accept=new Thread(this::acceptLoop,"microduck-lan-accept"); accept.start();
    }
    int port() { return listener.getLocalPort(); }
    String token() { return token; } // Show locally, never put into state/logs/test reports.
    private void acceptLoop() {
        while(!closed) {
            try {
                Socket socket=listener.accept(); socket.setTcpNoDelay(true); sockets.add(socket);
                try { clients.execute(()->serve(socket)); }
                catch(RejectedExecutionException e) { sockets.remove(socket); socket.close(); }
            } catch(IOException e) { if(!closed) close(); }
        }
    }
    private void serve(Socket socket) {
        ScheduledFuture<?> timeout;
        try { timeout=deadlines.schedule(()->{ try { socket.close(); } catch(IOException ignored) {} },3,TimeUnit.SECONDS); }
        catch(RejectedExecutionException e) { try { socket.close(); } catch(IOException ignored) {} sockets.remove(socket); return; }
        try(Socket connection=socket) {
            try { handle(connection); }
            catch(BadRequest e) { json(connection,e.status,"{\"error\":\"Invalid HTTP request\"}"); }
        } catch(IOException ignored) {
            // Disconnected peer or bounded socket timeout; no simulation work here.
        } finally { timeout.cancel(false); sockets.remove(socket); }
    }
    private void handle(Socket connection) throws IOException,BadRequest {
        InputStream in=connection.getInputStream(); long deadline=System.nanoTime()+2_000_000_000L;
        String request=line(connection,in,1024,deadline); String[] words=request.split(" ");
        if(words.length!=3 || !words[2].equals("HTTP/1.1") || !words[1].startsWith("/")) throw new BadRequest(400);
        Map<String,String> headers=new HashMap<>(); int total=request.length(),count=0;
        while(true) {
            String row=line(connection,in,4096,deadline); total+=row.length()+2;
            if(total>4096 || ++count>40) throw new BadRequest(431);
            if(row.isEmpty()) break;
            int colon=row.indexOf(':'); if(colon<=0) throw new BadRequest(400);
            String key=row.substring(0,colon).toLowerCase(Locale.ROOT);
            if(!key.matches("[a-z0-9-]+") || headers.put(key,row.substring(colon+1).trim())!=null) throw new BadRequest(400);
        }
        if(headers.containsKey("transfer-encoding")) throw new BadRequest(400);
        String path=words[1];
        if(words[0].equals("GET") && path.equals("/")) { respond(connection,200,"text/html; charset=utf-8",page); return; }
        if(words[0].equals("GET") && path.equals("/health")) { json(connection,200,"{\"service\":\"microduck-lan\",\"protocol\":1}"); return; }
        String auth=headers.getOrDefault("authorization","");
        if(!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))) {
            json(connection,401,"{\"error\":\"Pairing token required\"}"); return;
        }
        if(words[0].equals("GET") && path.equals("/api/state")) { json(connection,200,state.get()); return; }
        if(path.equals("/api/frame")) {
            if(!words[0].equals("GET")) throw new BadRequest(405);
            VideoFrames.Frame frame=video.get();
            if(frame==null) { json(connection,503,"{\"error\":\"Waiting for 3D frame; keep simulator visible\"}"); return; }
            respondHead(connection,200,"image/jpeg",frame.size(),
                "\r\nX-Frame-Sequence: "+frame.sequence+"\r\nX-Frame-Age-Ms: "+Math.max(0,(System.nanoTime()-frame.capturedNs)/1_000_000L));
            OutputStream out=connection.getOutputStream(); frame.writeTo(out); out.flush(); return;
        }
        if(!path.equals("/api/command")) { json(connection,404,"{\"error\":\"Unknown path\"}"); return; }
        if(!words[0].equals("POST")) throw new BadRequest(405);
        if(!headers.getOrDefault("content-type","").split(";",2)[0].trim().equalsIgnoreCase("application/json")) throw new BadRequest(415);
        int length;
        try { length=Integer.parseInt(headers.getOrDefault("content-length","")); }
        catch(NumberFormatException e) { throw new BadRequest(411); }
        if(length<1 || length>512) throw new BadRequest(413);
        byte[] body=new byte[length]; int offset=0;
        while(offset<length) {
            remaining(connection,deadline); int n=in.read(body,offset,length-offset);
            if(n<0) throw new BadRequest(400); offset+=n;
        }
        RemoteCommand command;
        try { command=RemoteCommand.parse(new String(body,StandardCharsets.UTF_8)); }
        catch(Exception e) { json(connection,400,"{\"error\":\"Invalid command or range\"}"); return; }
        try { json(connection,200,commands.apply(command)); }
        catch(CommandFailure e) { json(connection,e.status,"{\"error\":\"Command unavailable; retry or use local controls\"}"); }
        catch(Exception e) { json(connection,503,"{\"error\":\"Command failed\"}"); }
    }
    private static void remaining(Socket socket,long deadline) throws IOException {
        long left=deadline-System.nanoTime(); if(left<=0) throw new SocketTimeoutException("Request timeout");
        socket.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
    }
    private static String line(Socket socket,InputStream in,int max,long deadline) throws IOException,BadRequest {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(out.size()<=max) {
            remaining(socket,deadline); int value=in.read(); if(value<0) throw new BadRequest(400);
            if(value=='\r') { remaining(socket,deadline); if(in.read()!='\n') throw new BadRequest(400); return out.toString("US-ASCII"); }
            if(value<32 || value>126) throw new BadRequest(400); out.write(value);
        }
        throw new BadRequest(431);
    }
    private static void json(Socket socket,int status,String body) throws IOException {
        respond(socket,status,"application/json; charset=utf-8",body.getBytes(StandardCharsets.UTF_8));
    }
    private static void respond(Socket socket,int status,String type,byte[] body) throws IOException {
        respondHead(socket,status,type,body.length,"");
        OutputStream out=socket.getOutputStream(); out.write(body); out.flush();
    }
    private static void respondHead(Socket socket,int status,String type,int length,String extra) throws IOException {
        String head="HTTP/1.1 "+status+" Response\r\nContent-Type: "+type+"\r\nContent-Length: "+length
            +"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
            +"\r\nContent-Security-Policy: default-src 'self'; img-src 'self' blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
            +"\r\nReferrer-Policy: no-referrer"+extra+"\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
    }
    @Override public void close() {
        closed=true;
        try { listener.close(); } catch(IOException ignored) {}
        for(Socket socket:sockets) try { socket.close(); } catch(IOException ignored) {}
        clients.shutdownNow();
        deadlines.shutdownNow();
    }
}
