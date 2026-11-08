package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class SunReceiverBoundsChecks {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            long p=stack.nmalloc(16,SunReceiverBounds.BYTES);
            float[] actual={0,.5f,0,16,.5f,16};
            SunReceiverBounds.write(p,actual,2_000_000,64,-16,2_000_000.25,72,-15.5);
            float[] expected={-.25f,-7.5f,-.5f,15.75f,-7.5f,15.5f};
            for(int i=0;i<3;i++) {
                float lo=MemoryUtil.memGetFloat(p+i*4L),hi=MemoryUtil.memGetFloat(p+16+i*4L);
                check(lo<=expected[i]&&expected[i]-lo<.011f,"Lower bound lost geometry or read the record sentinel");
                check(hi>=expected[i+3]&&hi-expected[i+3]<.011f,"Upper bound lost geometry or read the record sentinel");
            }
            check(MemoryUtil.memGetInt(p+12)==0&&MemoryUtil.memGetInt(p+28)==0,"ABI padding is not initialized");
            check(MemoryUtil.memGetFloat(p+20)-MemoryUtil.memGetFloat(p+4)<.021f,"A flat water surface became a huge cube");
            SunReceiverBounds.write(p,null,0,0,0,0,0,0);
            check(MemoryUtil.memGetFloat(p)<-16&&MemoryUtil.memGetFloat(p+16)>239,"Legacy fallback is not conservative");
            System.out.println("PASS: actual water/mesh bounds, camera-relative precision, compact-position guard, 32-byte ABI and legacy fallback");
        }
    }
}
