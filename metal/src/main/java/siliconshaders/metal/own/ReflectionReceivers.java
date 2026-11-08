package siliconshaders.metal.own;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryUtil;

/** Conservative receiver metadata from the actual shader palette and mesher bounds; never GPU readback. */
final class ReflectionReceivers {
    static final int ALL=(1<<1)|(1<<2)|(1<<5);
    record Summary(int groups,int lo,int hi,int allLo,int allHi) {
        Summary(int groups,int lo,int hi){this(groups,lo,hi,0,0xffffff);}
    }
    static final Summary NONE=new Summary(0,0,0),UNKNOWN=new Summary(ALL,0,0xffffff);
    private static final int[] GROUPS=palette();
    private static int[] palette() {
        int[] groups=new int[256];java.util.Arrays.fill(groups,ALL);
        var row=Pattern.compile("\\s*\\{\\{([^}]+)\\},\\{([^}]+)\\}\\},?\\s*//\\s*(\\d+)\\s+(\\w+).* ".trim());
        try(var in=ReflectionReceivers.class.getResourceAsStream("/siliconshaders/own/terrain.metal")) {
            if(in==null)return groups;
            try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
                int count=0;for(String line;(line=reader.readLine())!=null;) {
                    if(line.trim().equals("};"))break;
                    var m=row.matcher(line);if(!m.matches())continue;
                    int id=Integer.parseInt(m.group(3));String[] optical=m.group(1).split(","),detail=m.group(2).split(",");
                    if(id!=count||id>=SurfaceMaterials.catalog().profiles().size()||!SurfaceMaterials.catalog().profiles().get(id).equals(m.group(4)))return unknownPalette();
                    int group=Integer.parseInt(optical[3].trim());
                    groups[id]=Float.parseFloat(detail[3].trim())>0&&Float.parseFloat(optical[2].trim())>0&&(group==1||group==2||group==5)?1<<group:0;count++;
                }
                if(count!=SurfaceMaterials.catalog().profiles().size())return unknownPalette();
            }
        }catch(Exception failure){return unknownPalette();}
        return groups;
    }
    private static int[] unknownPalette(){int[] a=new int[256];java.util.Arrays.fill(a,ALL);return a;}
    static int group(int material){return material>=0&&material<GROUPS.length?GROUPS[material]:ALL;}
    static int activeGroups(float[] tuning) {
        int at=siliconshaders.metal.sun.SunTuning.MATERIAL_START;
        if(tuning[at]<.5f||tuning[at+19]<.5f||tuning[at+20]<=0)return 0;
        return (tuning[at+5]>.5f?1<<1:0)|(tuning[at+6]>.5f?1<<2:0)|(tuning[at+9]>.5f?1<<5:0);
    }
    /**
     * Rain puddles trace with the world reflections (sun.metal surface_reflection_fs) on any open ground once the ground is soaked
     * (wetness above sunWetness's 0.35, after packWet): the pass must stay even with no tagged receiver in view.
     */
    static boolean puddles(float[] tuning) {
        int at=siliconshaders.metal.sun.SunTuning.MATERIAL_START;
        if(tuning[at]<.5f||tuning[at+19]<.5f||tuning[at+20]<=0)return false;
        float wet=tuning[siliconshaders.metal.sun.SunWeather.WET_SLOT];return Float.isFinite(wet)&&wet>.34f&&tuning[siliconshaders.metal.sun.SunWeather.WET_REFLECTION_RADIUS]>0;
    }
    static int union(int a,int b,boolean low){int out=0;for(int shift=0;shift<24;shift+=8)out|=(low?Math.min(a>>>shift&255,b>>>shift&255):Math.max(a>>>shift&255,b>>>shift&255))<<shift;return out;}
    static final class View {
        private final float[] planes=new float[16];private boolean valid;
        void set(long packet){valid=true;for(int i=0;i<16;i++){planes[i]=MemoryUtil.memGetFloat(packet+i*4L);valid&=Float.isFinite(planes[i]);}}
        boolean visible(Summary summary,int active,double x,double y,double z) {
            if((summary.groups&active)==0)return false;
            return boxVisible(summary.lo,summary.hi,x,y,z);
        }
        boolean puddle(Summary summary,float radius,double x,double y,double z,float margin) {
            int lo=summary.allLo,hi=summary.allHi;
            if(radius<=0)return false;
            if(!Double.isFinite(x+y+z)||!Float.isFinite(radius+margin))return true;
            for(int shift=0;shift<24;shift+=8)if((lo>>>shift&255)==0||(hi>>>shift&255)==255)return true;
            double lx=x+(lo&255)-16-margin,ly=y+(lo>>>8&255)-16-margin,lz=z+(lo>>>16&255)-16-margin;
            double hx=x+(hi&255)-16+margin,hy=y+(hi>>>8&255)-16+margin,hz=z+(hi>>>16&255)-16+margin;
            double dx=Math.max(lx,Math.max(-hx,0)),dy=Math.max(ly,Math.max(-hy,0)),dz=Math.max(lz,Math.max(-hz,0));
            return dx*dx+dy*dy+dz*dz<=radius*radius&&boxVisible(lo,hi,x,y,z);
        }
        private boolean boxVisible(int lo,int hi,double x,double y,double z) {
            if(!valid)return true;
            // Saturated/unknown bounds fail open, including models outside the packed coordinate range.
            for(int shift=0;shift<24;shift+=8)if((lo>>>shift&255)==0||(hi>>>shift&255)==255)return true;
            double lx=x+(lo&255)-16,ly=y+(lo>>>8&255)-16,lz=z+(lo>>>16&255)-16;
            double hx=x+(hi&255)-16,hy=y+(hi>>>8&255)-16,hz=z+(hi>>>16&255)-16;
            for(int i=0;i<16;i+=4){float a=planes[i],b=planes[i+1],c=planes[i+2];
                // The native cull's same four planes already contain wind expansion; add a numerical guard.
                if(a*(a>=0?hx:lx)+b*(b>=0?hy:ly)+c*(c>=0?hz:lz)+planes[i+3]<-.02*(Math.abs(a)+Math.abs(b)+Math.abs(c)))return false;
            }
            return true;
        }
    }
}
