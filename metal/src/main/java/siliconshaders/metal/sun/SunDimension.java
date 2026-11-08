package siliconshaders.metal.sun;

import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

/** Dimension policy for the native renderer. Independent Metal implementation of the reference's three world paths. */
public final class SunDimension {
    public static final int NETHER=-1, OVERWORLD=0, END=1;
    /**
     * The End's fixed oblique light, independent of the world's day clock: the black hole drawn by sunEndSky stands on this
     * direction, about 24 degrees above the horizon so it sits in a normal view. Without the black hole nothing casts
     * light: no direct light and no shadow pass. The Nether has no directional light.
     */
    public static final float END_ANGLE=-1.152f, END_STRENGTH=0.8f;
    public static final int END_BLACK_HOLE=SunTuning.index("endBlackHole"),END_PLANET=SunTuning.index("endPlanet"),
        END_RING_TRANSPARENCY=SunTuning.index("endRingTransparency"),END_FOG=SunTuning.index("endFog"),
        END_FOG_STRENGTH=SunTuning.index("endFogStrength"),END_FOG_DISTANCE=SunTuning.index("endFogDistance");
    // Dimension-specific values reuse the disabled aurora/nebula extension. Shared lighting stays 864 bytes.
    public static final int MODE_SLOT=15, RANGE_SLOT=19, HOLE_SLOT=20, PLANET_SLOT=21, RING_TRANSPARENCY_SLOT=22, FOG_STRENGTH_SLOT=23, FOG_DISTANCE_SLOT=24;
    public static final int FOG_BRIGHTNESS=SunTuning.index("endFogBrightness"),FOG_COLOR=SunTuning.index("endFogColorR"),FOG_HEIGHT=SunTuning.index("endFogHeight"),
        FOG_THICKNESS=SunTuning.index("endFogThickness"),FOG_SIZE=SunTuning.index("endFogSize"),FOG_WISPS=SunTuning.index("endFogWisps"),FOG_SPEED=SunTuning.index("endFogSpeed"),
        FOG_EMBER=SunTuning.index("endFogEmber"),FOG_EMBER_COLOR=SunTuning.index("endFogEmberColorR"),FOG_HOLE_LIGHT=SunTuning.index("endFogHoleLight");
    public static final int FOG_HOLE_LIGHT_SLOT=1,FOG_HEIGHT_SLOT=4,FOG_THICKNESS_SLOT=5,FOG_SIZE_SLOT=6,FOG_WISPS_SLOT=7,FOG_SPEED_SLOT=8,FOG_BRIGHTNESS_SLOT=9,FOG_EMBER_SLOT=10,FOG_COLOR_SLOT=12,FOG_EMBER_COLOR_SLOT=25;
    public static final int END_ELEVATION=SunTuning.index("endLightElevation"),END_LIGHT_STRENGTH=SunTuning.index("endLightStrength"),
        END_HOLE_SIZE=SunTuning.index("endHoleSize"),END_DISC=SunTuning.index("endDiscBrightness"),END_STARS=SunTuning.index("endStars");
    /** aurora[0].w: the black hole's size. p[14] is the sunset's, which the End's sky never reads: x disc brightness, y stars. */
    public static final int HOLE_SIZE_SLOT=3,DISC_SLOT=SunTuning.index("sunsetR"),STARS_SLOT=SunTuning.index("sunsetG");
    private SunDimension() {}
    /** The light's angle from the zenith for an elevation in degrees: the black hole stands on this direction. */
    public static float endAngle(float elevation) { return (float)Math.toRadians(Math.clamp(Float.isFinite(elevation)?elevation:24,5,85)-90); }
    public static float endStrength(float scale) { return END_STRENGTH*(Float.isFinite(scale)?Math.clamp(scale,0,2):1); }
    /** The End's values that live in the frame's own lighting packet copy, overwriting slots the End does not read. */
    public static void writeEndLook(long lighting,int kind,float[] tuning) {
        if(kind!=END)return;
        MemoryUtil.memPutFloat(lighting+DISC_SLOT*4L,checked(tuning,END_DISC));
        MemoryUtil.memPutFloat(lighting+STARS_SLOT*4L,checked(tuning,END_STARS));
    }

    public static int kind(DimensionType.Skybox sky,boolean ceiling) {
        return sky==DimensionType.Skybox.END?END:ceiling?NETHER:OVERWORLD;
    }
    public static boolean supported(DimensionType.Skybox sky,boolean ceiling) {
        return ceiling||sky==DimensionType.Skybox.OVERWORLD||sky==DimensionType.Skybox.END;
    }
    /** The black hole needs the native sky that draws it: light from something invisible is what it replaces. */
    public static boolean blackHole(float[] tuning) { return tuning[END_BLACK_HOLE]>.5f&&tuning[SunTuning.SKY_ENABLED]>.5f; }
    /** The same without a snapshot, for per-entity checks that must not copy the whole table. */
    public static boolean blackHole(SunTuning tuning) { return tuning.value(END_BLACK_HOLE)>.5f&&tuning.value(SunTuning.SKY_ENABLED)>.5f; }
    public static void write(long aurora,int kind,Vector3fc fog,float range,double cameraX,double cameraY,double cameraZ) {
        write(aurora,kind,fog,range,cameraX,cameraY,cameraZ,true,false);
    }
    /** Reuses the disabled aurora colors only in Nether/End: atmosphere RGB, dimension tag, border range, End bodies. */
    public static void write(long aurora,int kind,Vector3fc fog,float range,double cameraX,double cameraY,double cameraZ,boolean blackHole,boolean planet) {
        write(aurora,kind,fog,range,cameraX,cameraY,cameraZ,blackHole,planet,SunTuning.PARAMS.get(END_RING_TRANSPARENCY).neutral());
    }
    public static void write(long aurora,int kind,Vector3fc fog,float range,double cameraX,double cameraY,double cameraZ,boolean blackHole,boolean planet,float ringTransparency) {
        MemoryUtil.memPutFloat(aurora+MODE_SLOT*4L,kind);
        if(kind==OVERWORLD)return;
        float r=.075f,g=.08f,b=.105f;
        if(kind==NETHER) {
            float x=finite(fog.x()),y=finite(fog.y()),z=finite(fog.z());
            float length=(float)Math.sqrt(x*x+y*y+z*z);
            if(length>1e-5f){r=x*.6f+x/length*.2f;g=y*.6f+y/length*.2f;b=z*.6f+z/length*.2f;}
            else {r=.4f;g=.14f;b=.06f;}
        }
        MemoryUtil.memPutFloat(aurora+64,r);MemoryUtil.memPutFloat(aurora+68,g);MemoryUtil.memPutFloat(aurora+72,b);
        if(kind==END){
            MemoryUtil.memPutFloat(aurora+HOLE_SLOT*4L,blackHole?1:0);MemoryUtil.memPutFloat(aurora+PLANET_SLOT*4L,planet?1:0);
            MemoryUtil.memPutFloat(aurora+RING_TRANSPARENCY_SLOT*4L,Float.isFinite(ringTransparency)?Math.clamp(ringTransparency,0,1):SunTuning.PARAMS.get(END_RING_TRANSPARENCY).neutral());
            MemoryUtil.memPutFloat(aurora+FOG_STRENGTH_SLOT*4L,SunTuning.PARAMS.get(END_FOG_STRENGTH).neutral());
            MemoryUtil.memPutFloat(aurora+FOG_DISTANCE_SLOT*4L,SunTuning.PARAMS.get(END_FOG_DISTANCE).neutral());
            fogLook(aurora,FOG_DEFAULTS);
        }
        MemoryUtil.memPutFloat(aurora+RANGE_SLOT*4L,Float.isFinite(range)?Math.clamp(range,32,kind==NETHER?256:4096):128);
        MemoryUtil.memPutFloat(aurora,Double.isFinite(cameraX)?(float)cameraX:0);
        MemoryUtil.memPutFloat(aurora+8,Double.isFinite(cameraZ)?(float)cameraZ:0);
        MemoryUtil.memPutFloat(aurora+44,Double.isFinite(cameraY)?(float)cameraY:64);
    }
    /** Fog is independent of both End bodies. Only the End's GPU copy uses these slots. */
    public static void writeFog(long aurora,int kind,float[] tuning) {
        if(kind!=END)return;
        float strength=tuning[END_FOG_STRENGTH],distance=tuning[END_FOG_DISTANCE];
        strength=tuning[END_FOG]>.5f?(Float.isFinite(strength)?Math.clamp(strength,0,2):1):0;
        distance=Float.isFinite(distance)?Math.clamp(distance,32,512):128;
        MemoryUtil.memPutFloat(aurora+FOG_STRENGTH_SLOT*4L,strength);
        MemoryUtil.memPutFloat(aurora+FOG_DISTANCE_SLOT*4L,distance);
        fogLook(aurora,tuning);
    }
    private static final float[] FOG_DEFAULTS=fogDefaults();
    private static float[] fogDefaults(){float[] out=new float[SunTuning.COUNT];for(int i=0;i<out.length;i++)out[i]=SunTuning.PARAMS.get(i).neutral();return out;}
    /** The fog's look, in aurora slots nothing else reads in the End. Colors go linear here, once per frame on the CPU. */
    private static void fogLook(long aurora,float[] tuning) {
        for(int i=0;i<3;i++) {
            MemoryUtil.memPutFloat(aurora+(FOG_COLOR_SLOT+i)*4L,(float)Math.pow(checked(tuning,FOG_COLOR+i),2.2));
            MemoryUtil.memPutFloat(aurora+(FOG_EMBER_COLOR_SLOT+i)*4L,(float)Math.pow(checked(tuning,FOG_EMBER_COLOR+i),2.2));
        }
        MemoryUtil.memPutFloat(aurora+FOG_HEIGHT_SLOT*4L,checked(tuning,FOG_HEIGHT));
        MemoryUtil.memPutFloat(aurora+FOG_THICKNESS_SLOT*4L,checked(tuning,FOG_THICKNESS));
        MemoryUtil.memPutFloat(aurora+FOG_SIZE_SLOT*4L,checked(tuning,FOG_SIZE));
        MemoryUtil.memPutFloat(aurora+FOG_WISPS_SLOT*4L,checked(tuning,FOG_WISPS));
        MemoryUtil.memPutFloat(aurora+FOG_SPEED_SLOT*4L,checked(tuning,FOG_SPEED));
        MemoryUtil.memPutFloat(aurora+FOG_BRIGHTNESS_SLOT*4L,checked(tuning,FOG_BRIGHTNESS));
        MemoryUtil.memPutFloat(aurora+FOG_EMBER_SLOT*4L,checked(tuning,FOG_EMBER));
        MemoryUtil.memPutFloat(aurora+FOG_HOLE_LIGHT_SLOT*4L,checked(tuning,FOG_HOLE_LIGHT));
        MemoryUtil.memPutFloat(aurora+HOLE_SIZE_SLOT*4L,checked(tuning,END_HOLE_SIZE));
    }
    private static float checked(float[] tuning,int index){var param=SunTuning.PARAMS.get(index);float value=tuning[index];return Float.isFinite(value)?Math.clamp(value,param.min(),param.max()):param.neutral();}
    private static float finite(float v){return Float.isFinite(v)?Math.clamp(v,0,1):0;}
}
