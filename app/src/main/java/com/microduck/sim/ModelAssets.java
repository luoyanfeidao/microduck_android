package com.microduck.sim;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Preserve MJCF relative paths; verify each generated asset against its source checksum. */
final class ModelAssets {
    static File prepare(Context context) throws Exception {
        JSONObject manifest;
        try(InputStream input=context.getAssets().open("model/manifest.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192]; int n;
            while((n=input.read(buffer))!=-1) bytes.write(buffer,0,n);
            manifest=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
        File root=new File(context.getFilesDir(),"model");
        if(!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Cannot create model directory");
        JSONArray files=manifest.getJSONArray("files");
        for(int i=0;i<files.length();i++) {
            JSONObject entry=files.getJSONObject(i);
            String path=entry.getString("path"); File destination=new File(root,path);
            if(!destination.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))
                throw new IllegalStateException("Asset path escapes model directory");
            String expected=entry.getString("sha256");
            if(destination.isFile() && hash(destination).equals(expected)) continue;
            File parent=destination.getParentFile();
            if(!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create asset directory");
            File temp=new File(destination.getPath()+".tmp");
            try(InputStream input=context.getAssets().open("model/"+path); FileOutputStream output=new FileOutputStream(temp)) {
                byte[] buffer=new byte[65536]; int n;
                while((n=input.read(buffer))!=-1) output.write(buffer,0,n);
            }
            if(!hash(temp).equals(expected)) throw new IllegalStateException("Asset checksum mismatch: "+path);
            if(!temp.renameTo(destination)) throw new IllegalStateException("Cannot install asset: "+path);
        }
        return root;
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[65536]; int n;
            while((n=input.read(buffer))!=-1) digest.update(buffer,0,n);
        }
        StringBuilder s=new StringBuilder();
        for(byte b:digest.digest()) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private ModelAssets() {}
}
