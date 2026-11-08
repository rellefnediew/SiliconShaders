package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryUtil;

/** One conservative camera-relative box per translucent mesh, independent of sorted record placeholders. */
public final class SunReceiverBounds {
    public static final int BYTES=32;
    private SunReceiverBounds() {}
    public static void write(long address,float[] bounds,int x,int y,int z,double cx,double cy,double cz) {
        MemoryUtil.memSet(address,0,BYTES);
        put(address,bounds,0,x,cx);put(address,bounds,1,y,cy);put(address,bounds,2,z,cz);
    }
    private static void put(long address,float[] bounds,int axis,int origin,double camera) {
        float relative=(float)(origin-Math.floor(camera))+(float)(Math.floor(camera)-camera);
        // Legacy meshes without a cached box stay conservative, once per mesh.
        float lo=bounds==null?-16:bounds[axis],hi=bounds==null?239:bounds[axis+3];
        MemoryUtil.memPutFloat(address+axis*4L,relative+lo-.01f);
        MemoryUtil.memPutFloat(address+16+axis*4L,relative+hi+.01f);
    }
}
