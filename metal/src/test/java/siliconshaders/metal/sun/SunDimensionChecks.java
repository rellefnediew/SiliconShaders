package siliconshaders.metal.sun;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Dimension routing/packet boundaries and production frame fixtures for the native transition test. */
public final class SunDimensionChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        require(SunDimension.kind(DimensionType.Skybox.OVERWORLD,false)==0,"Overworld route");
        require(SunDimension.kind(DimensionType.Skybox.NONE,true)==-1,"Nether route");
        require(SunDimension.kind(DimensionType.Skybox.END,false)==1,"End route");
        require(!SunDimension.supported(DimensionType.Skybox.NONE,false),"Unknown skyless world routed to Overworld");
        float[] tuning=new float[SunTuning.COUNT];for(int i=0;i<tuning.length;i++)tuning[i]=SunTuning.PARAMS.get(i).neutral();
        long aurora=MemoryUtil.nmemAlloc(SunAurora.FLOATS*4L),frame=MemoryUtil.nmemAlloc(SunFrames.CASCADE_BYTES);
        try {
            for(int kind:new int[]{-1,1,0,-1,0}){
                SunAurora.write(aurora,tuning);
                SunDimension.write(aurora,kind,new Vector3f(.2f,.08f,.03f),128,0,64,0);
                require(MemoryUtil.memGetFloat(aurora+60)==kind,"Dimension tag did not reset on transition");
                if(kind!=0)require(MemoryUtil.memGetFloat(aurora+76)==128&&MemoryUtil.memGetFloat(aurora+44)==64,"Dimension range/height packet");
                for(int i=0;i<SunAurora.FLOATS;i++)require(Float.isFinite(MemoryUtil.memGetFloat(aurora+i*4L)),"Nonfinite dimension packet");
            }
            for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1}){
                SunDimension.write(aurora,-1,new Vector3f(bad),bad,Double.NaN,Double.NaN,Double.NaN);
                for(int i:new int[]{11,15,16,17,18,19})require(Float.isFinite(MemoryUtil.memGetFloat(aurora+i*4L)),"Invalid atmosphere escaped sanitizing");
            }
            // The End's two bodies have their own switches, and the black hole needs the sky that draws it.
            require(SunDimension.blackHole(tuning)&&tuning[SunDimension.END_PLANET]==0,"End body defaults");
            require(SunTuning.PARAMS.get(SunDimension.END_BLACK_HOLE).toggle()&&SunTuning.PARAMS.get(SunDimension.END_PLANET).toggle(),"End body switches");
            for(int hole=0;hole<2;hole++)for(int planet=0;planet<2;planet++){
                SunDimension.write(aurora,1,new Vector3f(),128,0,64,0,hole==1,planet==1);
                require(MemoryUtil.memGetFloat(aurora+SunDimension.HOLE_SLOT*4L)==hole&&MemoryUtil.memGetFloat(aurora+SunDimension.PLANET_SLOT*4L)==planet,"End body packet");
            }
            // Menu values reach an isolated existing sky-packet slot, including endpoints and legacy defaults.
            float neutral=SunTuning.PARAMS.get(SunDimension.END_RING_TRANSPARENCY).neutral();
            require(Math.abs(neutral-.06f)<1e-6,"Legacy ring opacity default changed");
            for(float transparency:new float[]{0,.5f,1,-1,2,Float.NaN}){
                SunAurora.write(aurora,tuning);
                SunDimension.write(aurora,1,new Vector3f(),128,0,64,0,true,true,transparency);
                float expected=Float.isFinite(transparency)?Math.clamp(transparency,0,1):neutral;
                require(MemoryUtil.memGetFloat(aurora+SunDimension.RING_TRANSPARENCY_SLOT*4L)==expected,"Ring transparency packet/clamping");
                require(MemoryUtil.memGetFloat(aurora+SunDimension.PLANET_SLOT*4L)==1&&MemoryUtil.memGetFloat(aurora+SunDimension.HOLE_SLOT*4L)==1,"Ring setting overlaps body switches");
            }
            for(float strength:new float[]{0,.5f,1,2,Float.NaN})for(int on=0;on<2;on++){
                float[] fog=tuning.clone();fog[SunDimension.END_FOG]=on;fog[SunDimension.END_FOG_STRENGTH]=strength;fog[SunDimension.END_FOG_DISTANCE]=192;
                SunDimension.write(aurora,1,new Vector3f(),128,0,64,0,true,true,.57f);
                SunDimension.writeFog(aurora,1,fog);
                float expected=on==0?0:Float.isFinite(strength)?strength:1;
                require(MemoryUtil.memGetFloat(aurora+SunDimension.FOG_STRENGTH_SLOT*4L)==expected&&MemoryUtil.memGetFloat(aurora+SunDimension.FOG_DISTANCE_SLOT*4L)==192,"End fog GPU values/disabled switch");
                require(Math.abs(MemoryUtil.memGetFloat(aurora+SunDimension.RING_TRANSPARENCY_SLOT*4L)-.57f)<1e-6&&MemoryUtil.memGetFloat(aurora+SunDimension.HOLE_SLOT*4L)==1,"End fog overlaps ring/body controls");
            }
            // The fog's look reaches its slots, leaves the camera, dimension tag and body switches alone, and stays sane.
            float[] look=tuning.clone();look[SunDimension.FOG_HEIGHT]=200;look[SunDimension.FOG_THICKNESS]=2;look[SunDimension.FOG_SIZE]=3;look[SunDimension.FOG_WISPS]=.5f;look[SunDimension.FOG_SPEED]=4;look[SunDimension.FOG_BRIGHTNESS]=2.5f;look[SunDimension.FOG_EMBER]=1.5f;look[SunDimension.FOG_HOLE_LIGHT]=.25f;look[SunDimension.FOG_COLOR]=1;look[SunDimension.FOG_EMBER_COLOR+2]=1;
            SunDimension.write(aurora,1,new Vector3f(),128,10,64,20,true,false,.5f);SunDimension.writeFog(aurora,1,look);
            int[] slots={SunDimension.FOG_HEIGHT_SLOT,SunDimension.FOG_THICKNESS_SLOT,SunDimension.FOG_SIZE_SLOT,SunDimension.FOG_WISPS_SLOT,SunDimension.FOG_SPEED_SLOT,SunDimension.FOG_BRIGHTNESS_SLOT,SunDimension.FOG_EMBER_SLOT,SunDimension.FOG_HOLE_LIGHT_SLOT,SunDimension.FOG_COLOR_SLOT,SunDimension.FOG_EMBER_COLOR_SLOT+2};
            float[] wanted={200,2,3,.5f,4,2.5f,1.5f,.25f,1,1};
            for(int i=0;i<slots.length;i++)require(Math.abs(MemoryUtil.memGetFloat(aurora+slots[i]*4L)-wanted[i])<1e-5,"End fog look slot "+slots[i]);
            require(MemoryUtil.memGetFloat(aurora)==10&&MemoryUtil.memGetFloat(aurora+8)==20&&MemoryUtil.memGetFloat(aurora+44)==64&&MemoryUtil.memGetFloat(aurora+60)==1&&MemoryUtil.memGetFloat(aurora+SunDimension.HOLE_SLOT*4L)==1,"End fog look overlaps the camera, tag or switches");
            float[] broken=tuning.clone();broken[SunDimension.FOG_HEIGHT]=Float.NaN;broken[SunDimension.FOG_SIZE]=Float.POSITIVE_INFINITY;broken[SunDimension.FOG_THICKNESS]=99;SunDimension.writeFog(aurora,1,broken);
            require(MemoryUtil.memGetFloat(aurora+SunDimension.FOG_HEIGHT_SLOT*4L)==68&&MemoryUtil.memGetFloat(aurora+SunDimension.FOG_SIZE_SLOT*4L)==1&&MemoryUtil.memGetFloat(aurora+SunDimension.FOG_THICKNESS_SLOT*4L)==3,"End fog look not sanitized");
            // The light's place and strength, the hole's size, the disc and the stars.
            require(Math.abs(SunDimension.endAngle(tuning[SunDimension.END_ELEVATION])-SunDimension.END_ANGLE)<.01f&&SunDimension.endStrength(tuning[SunDimension.END_LIGHT_STRENGTH])==SunDimension.END_STRENGTH,"End light defaults moved");
            require(Math.abs(Math.cos(SunDimension.endAngle(90))-Math.sin(Math.toRadians(85)))<1e-5&&Float.isFinite(SunDimension.endAngle(Float.NaN))&&SunDimension.endStrength(Float.NaN)==SunDimension.END_STRENGTH&&SunDimension.endStrength(9)==SunDimension.END_STRENGTH*2,"End light not sanitized");
            require(SunTuning.index("sunsetR")/4==14&&SunTuning.index("sunsetG")/4==14,"End look left the sunset's packet slot");
            {long packet=MemoryUtil.nmemAlloc(SunTuning.LIGHTING_COUNT*4L);try{float[] end=tuning.clone();end[SunDimension.END_DISC]=2;end[SunDimension.END_STARS]=.5f;end[SunDimension.END_HOLE_SIZE]=1.5f;
                SunDimension.writeEndLook(packet,1,end);SunDimension.writeFog(aurora,1,end);
                require(MemoryUtil.memGetFloat(packet+SunDimension.DISC_SLOT*4L)==2&&MemoryUtil.memGetFloat(packet+SunDimension.STARS_SLOT*4L)==.5f&&MemoryUtil.memGetFloat(aurora+SunDimension.HOLE_SIZE_SLOT*4L)==1.5f,"End look slots");
                MemoryUtil.memPutFloat(packet+SunDimension.DISC_SLOT*4L,7);SunDimension.writeEndLook(packet,0,end);require(MemoryUtil.memGetFloat(packet+SunDimension.DISC_SLOT*4L)==7,"End look overwrites the Overworld's sunset");
            }finally{MemoryUtil.nmemFree(packet);}}
            float[] noSky=tuning.clone();noSky[SunTuning.SKY_ENABLED]=0;require(!SunDimension.blackHole(noSky),"Black hole light without the sky that draws it");
            float[] noHole=tuning.clone();noHole[SunDimension.END_BLACK_HOLE]=0;require(!SunDimension.blackHole(noHole),"Black hole switch ignored");
            if(args.length>0){
                Path out=Path.of(args[0]);Files.createDirectories(out);
                // Repeat Nether after End and return to Overworld at matching coordinates, then disable the shader.
                int[] dimensions={0,-1,1,1,-1,0,0},sizes={512,0,0,512,0,512,0};
                var view=new Matrix4f().set(.2f,0,0,0, 0,0,.1f,0, 0,.2f,0,0, 0,0,1.2f,1);
                for(int i=0;i<dimensions.length;i++){
                    int kind=dimensions[i];boolean enabled=i<dimensions.length-1;
                    float angle=kind==1?SunDimension.END_ANGLE:0;
                    float strength=kind<0?0:kind>0?SunDimension.END_STRENGTH:1;
                    SunFrames.writeCascaded(frame,angle,0,8,0,sizes[i],enabled,false,1,i,strength,view,1f/60,i,64,3);
                    byte[] bytes=new byte[SunFrames.CASCADE_BYTES];MemoryUtil.memByteBuffer(frame,bytes.length).get(bytes);
                    Files.write(out.resolve(i+".frame"),bytes);
                    SunAurora.write(aurora,tuning);SunDimension.write(aurora,kind,new Vector3f(.2f,.08f,.03f),128,0,64,0);
                    bytes=new byte[SunAurora.FLOATS*4];MemoryUtil.memByteBuffer(aurora,bytes.length).get(bytes);Files.write(out.resolve(i+".aurora"),bytes);
                }
            }
        } finally {MemoryUtil.nmemFree(aurora);MemoryUtil.nmemFree(frame);}
        System.out.println("PASS: Overworld/Nether/End routing, unknown dimension guard, packet transitions and finite atmosphere; production native fixtures");
    }
}
