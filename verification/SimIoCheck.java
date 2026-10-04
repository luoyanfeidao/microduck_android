package com.microduck.sim;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Actual socket server, strict protocol, single-owner dispatch and cancelled queued work. */
public final class SimIoCheck {
    static final String HELLO="{\"op\":\"hello\",\"protocol\":1,\"joints\":15}";
    static final class Link implements AutoCloseable {
        final Socket socket;final BufferedReader input;final Writer output;
        Link(int port)throws Exception{
            socket=new Socket("127.0.0.1",port);socket.setSoTimeout(2000);
            input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            output=new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8);
        }
        String call(String line)throws Exception{output.write(line+"\n");output.flush();return input.readLine();}
        public void close()throws IOException{socket.close();}
    }
    public static void main(String[] args)throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"test-simulation-worker"));
        AtomicInteger applied=new AtomicInteger(),disconnects=new AtomicInteger();
        SimIoServer.Body body=new SimIoServer.Body(){
            public String apply(SimIoRequest request){
                if(!Thread.currentThread().getName().equals("test-simulation-worker"))throw new AssertionError("Wrong native owner");
                applied.incrementAndGet();return request.op.equals("hello")?"{\"protocol\":1}":"{}";
            }
            public void disconnected(){disconnects.incrementAndGet();}
        };
        try{
            for(String bad:new String[]{"{}","{\"op\":\"hello\",\"protocol\":2,\"joints\":15}",
                "{\"op\":\"hello\",\"protocol\":1,\"joints\":14}","{\"op\":\"gain\",\"kp\":-1}",
                "{\"op\":\"gain\",\"kp\":1.5}","{\"op\":\"gain\",\"kp\":65536}",
                "{\"op\":\"torque\",\"on\":1}","{\"op\":\"read\",\"extra\":1}",
                "{\"op\":\"write\",\"targets\":[0]}","{\"op\":\"tof\"}"}){
                boolean rejected=false;try{SimIoRequest.parse(bad);}catch(Exception e){rejected=true;}
                if(!rejected)throw new AssertionError("Invalid request accepted");
            }
            try(SimIoServer server=new SimIoServer(0,worker,body);Link link=new Link(server.port())){
                if(new JSONObject(link.call(HELLO)).getInt("protocol")!=1)throw new AssertionError("Handshake");
                if(!link.call("{\"op\":\"gain\",\"kp\":200}").equals("{}"))throw new AssertionError("Ack");
                int before=applied.get();
                if(!new JSONObject(link.call("{\"op\":\"torque\",\"on\":\"true\"}")).has("error"))throw new AssertionError("Refusal");
                if(applied.get()!=before)throw new AssertionError("Invalid command dispatched");
            }
            try(SimIoServer server=new SimIoServer(0,worker,body);Link link=new Link(server.port())){
                if(!new JSONObject(link.call("{\"op\":\"read\"}")).has("error"))throw new AssertionError("Missing hello accepted");
            }
            // An occupied worker makes the queued hello time out; it must never execute later.
            CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
            worker.execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            blocked.await();int before=applied.get();
            try(SimIoServer server=new SimIoServer(0,worker,body);Link link=new Link(server.port())){
                if(!new JSONObject(link.call(HELLO)).has("error"))throw new AssertionError("Worker timeout not reported");
            }finally{release.countDown();}
            worker.submit(()->{}).get(2,TimeUnit.SECONDS);
            if(applied.get()!=before)throw new AssertionError("Timed-out mutation applied");
            try(SimIoServer server=new SimIoServer(0,worker,body);Link link=new Link(server.port())){
                link.call(HELLO);
                try(Link other=new Link(server.port())){if(other.input.readLine()!=null)throw new AssertionError("Second controller admitted");}
                if(link.input.readLine()!=null)throw new AssertionError("Idle client not disconnected");
            }
            try(SimIoServer server=new SimIoServer(0,worker,body);Link link=new Link(server.port())){
                link.output.write("x".repeat(4097));link.output.flush();
                if(link.input.readLine()!=null)throw new AssertionError("Oversized line accepted");
            }
            worker.submit(()->{}).get(2,TimeUnit.SECONDS);
            if(disconnects.get()<4)throw new AssertionError("Disconnect not delivered");
            System.out.println("{\"strict_protocol_and_validation\":true,\"worker_ownership\":true,\"timeout_cancels_queued_mutation\":true,\"single_client\":true,\"disconnect_and_idle_deadline\":true,\"line_bound\":true}");
        }finally{worker.shutdownNow();}
    }
}
