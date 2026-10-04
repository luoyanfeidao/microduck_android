package com.microduck.sim;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** One stock Rust RemoteIo client, USB adb-forward transport, no JNI on socket threads. */
final class SimIoServer implements AutoCloseable {
    interface Body {
        String apply(SimIoRequest request) throws Exception; // simulation worker only
        void disconnected(); // simulation worker only
    }
    private final ServerSocket listener=new ServerSocket();
    private final AtomicReference<Socket> client=new AtomicReference<>();
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final Executor worker;
    private final Body body;
    private volatile boolean closed;
    SimIoServer(int port,Executor worker,Body body) throws IOException {
        this.worker=worker;this.body=body;
        try {
            listener.setReuseAddress(true);
            // Stock protocol has no authentication. Keep it private; adb forward provides access.
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),1);
        } catch(IOException e) {listener.close();io.shutdownNow();throw e;}
        new Thread(this::accept,"microduck-simio-accept").start();
    }
    int port(){return listener.getLocalPort();}
    private void accept() {
        while(!closed) try {
            Socket socket=listener.accept();socket.setTcpNoDelay(true);socket.setSoTimeout(500);
            if(closed || !client.compareAndSet(null,socket)){socket.close();continue;}
            try{io.execute(()->serve(socket));}
            catch(RuntimeException e){client.compareAndSet(socket,null);socket.close();}
        } catch(IOException e){if(!closed)close();}
    }
    private String exchange(Socket socket,SimIoRequest request) throws Exception {
        FutureTask<String> result=new FutureTask<>(()->{
            if(closed || socket.isClosed() || client.get()!=socket) throw new IOException("SimIo session ended");
            return body.apply(request);
        });
        worker.execute(result);
        try{return result.get(150,TimeUnit.MILLISECONDS);}
        finally{result.cancel(false);} // a timed-out queued mutation must not execute later
    }
    private void serve(Socket socket) {
        boolean hello=false;
        try(Socket connection=socket) {
            InputStream input=connection.getInputStream();
            while(!closed) {
                String line=readLine(input);if(line==null)break;
                try {
                    SimIoRequest request=SimIoRequest.parse(line);
                    if(!hello && !request.op.equals("hello")) throw new IOException("hello required");
                    if(hello && request.op.equals("hello")) throw new IOException("Already handshaken");
                    String answer=exchange(connection,request);
                    connection.getOutputStream().write((answer+"\n").getBytes(StandardCharsets.UTF_8));
                    hello=true;
                } catch(Exception e) {
                    String error="{\"error\":"+JSONObject.quote("SimIo request rejected: "+e.getClass().getSimpleName())+"}";
                    connection.getOutputStream().write((error+"\n").getBytes(StandardCharsets.UTF_8));
                    break;
                }
            }
        } catch(IOException ignored) {
            // Disconnect and read deadline are handled identically below.
        } finally {
            try{worker.execute(body::disconnected);}catch(RuntimeException ignored){}
            client.compareAndSet(socket,null);
        }
    }
    private static String readLine(InputStream input) throws IOException {
        long deadline=System.nanoTime()+1_000_000_000L;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(;;) {
            int b=input.read();
            if(b==-1){if(bytes.size()==0)return null;throw new IOException("Truncated line");}
            if(System.nanoTime()>deadline)throw new IOException("Line deadline");
            if(b=='\n')return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            if(bytes.size()>=4096)throw new IOException("Line too large");
            bytes.write(b);
        }
    }
    @Override public void close() {
        closed=true;
        try{listener.close();}catch(IOException ignored){}
        Socket socket=client.getAndSet(null);
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
        io.shutdownNow();
    }
}
