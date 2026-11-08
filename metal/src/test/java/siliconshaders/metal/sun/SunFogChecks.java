package siliconshaders.metal.sun;

import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

public final class SunFogChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) {
        float previous=0;
        for(int sky=-1;sky<=16;sky++) {
            float x=SunFog.exposure(sky);require(x>=previous&&x>=0&&x<=1,"Camera exposure range/continuity");previous=x;
        }
        require(SunFog.exposure(0)==0&&SunFog.exposure(2)==0&&SunFog.exposure(8)==1&&SunFog.exposure(15)==1,"Cave/outdoor endpoints");
        require(SunFog.exposure(0,true)==1&&SunFog.exposure(2,true)==1,"Dark outdoor foliage suppressed fog");
        require(SunFog.height(67,63)==4&&SunFog.height(40,63)==0&&SunFog.height(Double.NaN,63)==0&&SunFog.HEIGHT_SLOT==11&&SunFog.HEIGHT_SLOT<SunAurora.FLOATS,"Camera height for the haze");
        long p=MemoryUtil.nmemAlloc(SunTuning.COUNT*4L);
        try {
            for(int sky:new int[]{0,4,8,15})for(int enabled=0;enabled<=1;enabled++) {
                for(int i=0;i<SunTuning.COUNT;i++)MemoryUtil.memPutFloat(p+i*4L,SunTuning.PARAMS.get(i).neutral());
                MemoryUtil.memPutFloat(p+SunTuning.SKY_ENABLED*4L,enabled);
                byte[] before=new byte[SunTuning.COUNT*4];MemoryUtil.memByteBuffer(p,before.length).get(before);
                SunFog.write(p,sky);
                require(MemoryUtil.memGetFloat(p+SunTuning.SKY_ENABLED*4L)==(enabled==0?0:2+SunFog.exposure(sky)),"GPU exposure encoding");
                MemoryUtil.memPutFloat(p+SunTuning.SKY_ENABLED*4L,enabled);
                byte[] after=new byte[before.length];MemoryUtil.memByteBuffer(p,after.length).get(after);require(Arrays.equals(before,after),"Fog encoding overlaps another control");
            }
        } finally {MemoryUtil.nmemFree(p);}
        System.out.println("PASS: camera fog exposure, cave/outdoor endpoints, disabled sky and isolated GPU-only slot; lighting ABI unchanged");
    }
}
