package com.microduck.sim;

import java.nio.ByteBuffer;

/** GLES bottom-up RGBA bytes -> Android top-down ARGB; sensor mount rotation stays in browser. */
final class CameraPixels {
    static void toArgb(ByteBuffer rgba,int width,int height,int[] argb) {
        if(width<1 || height<1 || width>640 || height>480 || rgba.capacity()!=width*height*4 || argb.length!=width*height)
            throw new IllegalArgumentException("Invalid camera pixel buffer");
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int at=((height-1-y)*width+x)*4;
            argb[y*width+x]=0xff000000|((rgba.get(at)&255)<<16)|((rgba.get(at+1)&255)<<8)|(rgba.get(at+2)&255);
        }
    }
    private CameraPixels() {}
}
