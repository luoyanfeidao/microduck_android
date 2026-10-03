package com.microduck.sim;

final class SceneFrameCheck {
    public static void main(String[] args) {
        float[] packet=new float[SceneFrame.HEADER+SceneFrame.STRIDE+3+SceneFrame.CAMERA_TAIL];
        packet[0]=2; packet[1]=1; packet[2]=1;
        int at=SceneFrame.HEADER;
        packet[at]=0; packet[at+1]=2; packet[at+2]=3; packet[at+3]=4;
        float[] rotation={0,-1,0,1,0,0,0,0,1}; System.arraycopy(rotation,0,packet,at+4,9);
        SceneFrame frame=new SceneFrame(packet,1); float[] matrix=new float[16]; frame.modelMatrix(0,matrix);
        float x=matrix[0]+matrix[12],y=matrix[1]+matrix[13],z=matrix[2]+matrix[14];
        if(x!=2 || y!=4 || z!=4 || matrix[15]!=1) throw new AssertionError("Rotation/translation transpose error");
        boolean rejected=false; packet[at+1]=Float.NaN;
        try { new SceneFrame(packet,2); } catch(IllegalArgumentException e) { rejected=true; }
        if(!rejected) throw new AssertionError("Non-finite snapshot accepted");
        rejected=false;
        try { new SceneFrame(new float[]{1,1},3); } catch(IllegalArgumentException e) { rejected=true; }
        if(!rejected) throw new AssertionError("Truncated snapshot accepted");
        System.out.println("{\"matrix_mapping\":\"passed\",\"nan_and_truncation_rejection\":\"passed\"}");
    }
}
