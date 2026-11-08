package siliconshaders.metal.sun;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Compatible current-view packet. Receiver culling no longer depends on exact-camera history. */
public final class SunSurfaceFrame {
    public static final int BYTES=112;
    public static final int NO_RECEIVER_GRID=1, DEFERRED_LIGHTING=2;
    /** Independent of the menu master switch: false keeps conservative frustum culling. */
    public static final boolean RECEIVER_CULLING=Boolean.parseBoolean(System.getProperty("siliconshaders.sun.receiverCulling","true"));
    private SunSurfaceFrame() {}
    public static void write(long p,Matrix4fc clip,double x,double y,double z,long revision,long world,boolean enabled) {
        write(p,clip,x,y,z,revision,world,enabled,true);
    }
    public static void write(long p,Matrix4fc clip,double x,double y,double z,long revision,long world,boolean enabled,boolean receiverCulling) {
        write(p,clip,x,y,z,revision,world,enabled,receiverCulling,false);
    }
    public static void write(long p,Matrix4fc clip,double x,double y,double z,long revision,long world,boolean enabled,boolean receiverCulling,boolean deferredLighting) {
        MemoryUtil.memSet(p,0,BYTES);clip.getToAddress(p);
        MemoryUtil.memPutDouble(p+64,x);MemoryUtil.memPutDouble(p+72,y);MemoryUtil.memPutDouble(p+80,z);
        MemoryUtil.memPutLong(p+88,revision);MemoryUtil.memPutLong(p+96,world);MemoryUtil.memPutInt(p+104,enabled?1:0);
        MemoryUtil.memPutInt(p+108,(enabled&&!receiverCulling?NO_RECEIVER_GRID:0)|(deferredLighting?DEFERRED_LIGHTING:0));
    }
}
