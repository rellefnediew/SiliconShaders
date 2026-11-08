package siliconshaders.metal.sun;

import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Shared runtime/test packet: physical sun followed by two nearby light samples and their blend/cache metadata. */
public final class SunFrames {
    public static final int BASE_BYTES=144,BLEND_BYTES=464;
    private static final double TURN=2.0*Math.PI;
    private static final int SOLAR_SAMPLES=12566;
    public static final double ANGLE_STEP=TURN/SOLAR_SAMPLES;
    private SunFrames() {}
    public static int bytes(boolean smooth) { return smooth?BLEND_BYTES:BASE_BYTES; }
    public static void write(long p,float angle,double x,double y,double z,int size,boolean enabled,boolean smooth,
        int sections,long geometryRevision,float strength) {
        MemoryUtil.memSet(p,0,bytes(smooth));
        if(!enabled)return;
        writeLight(p,angle,x,y,z,size,sections,strength);
        if(smooth) {
            double phase=angle%TURN;if(phase<0)phase+=TURN;
            int lower=Math.min(SOLAR_SAMPLES-1,(int)Math.floor(phase/ANGLE_STEP));
            writeLight(p+144,(float)(lower*ANGLE_STEP),x,y,z,size,sections,strength);
            writeLight(p+288,(float)(((lower+1)%SOLAR_SAMPLES)*ANGLE_STEP),x,y,z,size,sections,strength);
            MemoryUtil.memPutFloat(p+432,(float)Math.max(0,Math.min(1,(phase-lower*ANGLE_STEP)/ANGLE_STEP)));
            MemoryUtil.memPutInt(p+436,size>0?1:0);MemoryUtil.memPutLong(p+440,geometryRevision);
            MemoryUtil.memPutInt(p+448,anchor(x));MemoryUtil.memPutInt(p+452,anchor(y));MemoryUtil.memPutInt(p+456,anchor(z));
        }
    }
    public static final int TEMPORAL_BYTES=256;
    public static void writeTemporal(long p,float angle,double x,double y,double z,int size,boolean enabled,
        int sections,long revision,float strength,org.joml.Matrix4fc view,float seconds,int sequence) {
        MemoryUtil.memSet(p,0,TEMPORAL_BYTES);
        write(p,angle,x,y,z,size,enabled,false,sections,revision,strength);
        view.getToAddress(p+144);
        MemoryUtil.memPutDouble(p+208,x);MemoryUtil.memPutDouble(p+216,y);MemoryUtil.memPutDouble(p+224,z);
        MemoryUtil.memPutLong(p+232,revision);MemoryUtil.memPutFloat(p+240,seconds);MemoryUtil.memPutInt(p+244,sequence);
    }
    public static final int CASCADE_BYTES=688;
    public static final float NEAR_RANGE=24,FAR_RANGE=64;
    /** Same sun direction; the detailed near map is preserved while the far map uses fewer pixels. */
    public static void writeCascaded(long p,float angle,double x,double y,double z,int size,boolean enabled,boolean smooth,
        int sections,long revision,float strength,org.joml.Matrix4fc view,float seconds,int sequence) {
        writeCascaded(p,angle,x,y,z,size,enabled,smooth,sections,revision,strength,view,seconds,sequence,64);
    }
    public static void writeCascaded(long p,float angle,double x,double y,double z,int size,boolean enabled,boolean smooth,
        int sections,long revision,float strength,org.joml.Matrix4fc view,float seconds,int sequence,int distance) {
        writeCascaded(p,angle,x,y,z,size,enabled,smooth,sections,revision,strength,view,seconds,sequence,distance,3);
    }
    public static int cascadeOffset(int index) { return index==0?0:256+(index-1)*BASE_BYTES; }
    public static int cascadeSize(int size,int index) { return size>0?Math.max(256,size>>index):0; }
    public static float cascadeRange(int distance,int index,int levels) {
        return index==0?NEAR_RANGE:index==levels-1?distance:(float)(NEAR_RANGE*Math.pow(distance/NEAR_RANGE,(double)index/(levels-1)));
    }
    public static void writeCascaded(long p,float angle,double x,double y,double z,int size,boolean enabled,boolean smooth,
        int sections,long revision,float strength,org.joml.Matrix4fc view,float seconds,int sequence,int distance,int levels) {
        if(levels<2||levels>4)throw new IllegalArgumentException("Shadow levels must be 2–4");
        MemoryUtil.memSet(p,0,CASCADE_BYTES);
        writeTemporal(p,angle,x,y,z,size,enabled,sections,revision,strength,view,seconds,sequence);
        if(!enabled)return;
        for(int i=0;i<levels;i++)writeLight(p+cascadeOffset(i),angle,x,y,z,cascadeSize(size,i),sections,strength,cascadeRange(distance,i,levels));
        MemoryUtil.memPutInt(p+252,levels);
        MemoryUtil.memPutInt(p+248,smooth?1:0);
    }
    private static int anchor(double position) { return (int)Math.floor(position/2.0)*2+1; }
    private static void writeLight(long p,float angle,double x,double y,double z,int size,int sections,float strength) {
        writeLight(p,angle,x,y,z,size,sections,strength,FAR_RANGE);
    }
    private static void writeLight(long p,float angle,double x,double y,double z,int size,int sections,float strength,float range) {
        var sun=new Vector3f(-(float)Math.sin(angle),(float)Math.cos(angle),0).normalize();
        SunProjection.create(sun,x,y,z,size,range).getToAddress(p);
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        MemoryUtil.memPutInt(p+64,bx);MemoryUtil.memPutInt(p+68,by);MemoryUtil.memPutInt(p+72,bz);
        MemoryUtil.memPutFloat(p+80,(float)(bx-x));MemoryUtil.memPutFloat(p+84,(float)(by-y));MemoryUtil.memPutFloat(p+88,(float)(bz-z));
        MemoryUtil.memPutFloat(p+96,sun.x);MemoryUtil.memPutFloat(p+100,sun.y);MemoryUtil.memPutFloat(p+104,sun.z);MemoryUtil.memPutFloat(p+108,strength);
        MemoryUtil.memPutFloat(p+112,range);MemoryUtil.memPutFloat(p+116,1.0f/Math.max(1,size));
        MemoryUtil.memPutFloat(p+120,0.00015f*128/Math.max(128,2*range));MemoryUtil.memPutFloat(p+124,size>0?1:0);
        MemoryUtil.memPutInt(p+128,sections);MemoryUtil.memPutInt(p+136,size);MemoryUtil.memPutInt(p+140,1);
    }
}
