package siliconshaders.metal.sun;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** CPU-only packet checks, independent of Minecraft and graphics initialization. */
public final class SunSurfaceChecks {
    public static void main(String[] args) {
        long p=MemoryUtil.nmemAlloc(SunSurfaceFrame.BYTES);
        try {
            SunSurfaceFrame.write(p,new Matrix4f(),12.5,-64,1e6,0x123456789abcdefL,5,true);
            require(MemoryUtil.memGetFloat(p)==1 && MemoryUtil.memGetFloat(p+60)==1);
            require(MemoryUtil.memGetDouble(p+64)==12.5 && MemoryUtil.memGetDouble(p+72)==-64 && MemoryUtil.memGetDouble(p+80)==1e6);
            require(MemoryUtil.memGetLong(p+88)==0x123456789abcdefL && MemoryUtil.memGetLong(p+96)==5);
            require(MemoryUtil.memGetInt(p+104)==1 && MemoryUtil.memGetInt(p+108)==0);
            SunSurfaceFrame.write(p,new Matrix4f(),0,0,0,0,0,false);require(MemoryUtil.memGetInt(p+104)==0);
            SunSurfaceFrame.write(p,new Matrix4f(),12.5,-64,1e6,7,5,true,false);
            require(MemoryUtil.memGetInt(p+104)==1 && MemoryUtil.memGetInt(p+108)==SunSurfaceFrame.NO_RECEIVER_GRID);
            require(MemoryUtil.memGetDouble(p+64)==12.5 && MemoryUtil.memGetLong(p+88)==7);
            SunSurfaceFrame.write(p,new Matrix4f(),0,0,0,0,0,false,false);
            require(MemoryUtil.memGetInt(p+104)==0 && MemoryUtil.memGetInt(p+108)==0);
            SunSurfaceFrame.write(p,new Matrix4f(),0,0,0,0,0,true,true);
            require(MemoryUtil.memGetInt(p+104)==1 && MemoryUtil.memGetInt(p+108)==0);
            SunSurfaceFrame.write(p,new Matrix4f(),0,0,0,0,0,false,false,true);
            require(MemoryUtil.memGetInt(p+104)==0 && MemoryUtil.memGetInt(p+108)==SunSurfaceFrame.DEFERRED_LIGHTING);
            SunSurfaceFrame.write(p,new Matrix4f(),0,0,0,0,0,true,false,true);
            require(MemoryUtil.memGetInt(p+104)==1 && MemoryUtil.memGetInt(p+108)==(SunSurfaceFrame.DEFERRED_LIGHTING|SunSurfaceFrame.NO_RECEIVER_GRID));
            var changes=new SunHistoryChanges();long serial=changes.serial();
            for(int i=0;i<2048;i++)changes.changed(i,0,0);
            require(changes.serial()==serial+2048);changes.consume(new Matrix4f(),0,0,0,64);
            require(changes.serial()==serial+2048);
            System.out.println("PASS: surface packet offsets, independent receiver-grid flag/reset and global terrain serial survive overflow/consumption (CPU only)");
        } finally { MemoryUtil.nmemFree(p); }
    }
    private static void require(boolean ok){if(!ok)throw new AssertionError("Surface frame contract failed");}
}
