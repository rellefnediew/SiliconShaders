package siliconshaders.metal.sun;

import java.nio.file.*;
import java.util.*;
import siliconshaders.metal.settings.*;
import org.lwjgl.system.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The custom sky's packet, phase controls, stable lighting prefix, profiles and real vanilla hook targets. */
public final class SunCelestialChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static float read(long p,int i){return MemoryUtil.memGetFloat(p+4L*i);}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("siliconshaders-celestial-");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var tuning=new SunTuning(dir);float[] values=tuning.values(),before=values.clone();
            require(SunTuning.LIGHTING_COUNT==76&&SunTuning.MOON_LIGHT==71,"Existing lighting offsets changed");
            long p=stack.nmalloc(16,240);MemoryUtil.memSet(p,0x55,240);
            SunCelestial.write(p,values,0);require(read(p,14)==0,"Default must preserve vanilla textures");
            require(SunCelestial.FLOATS==56&&MemoryUtil.memGetInt(p+224)==0x55555555,"Celestial packet overran its 224 bytes");
            require(read(p,16)==1&&read(p,17)==0&&read(p,18)==.25f,"Default phase rotation/softness");
            require(Math.abs(read(p,3)-(float)Math.toRadians(3))<1e-6,"Sun radius is not radians");
            require(read(p,0)==16&&Math.abs(read(p,1)-(float)Math.pow(.9,2.2)*16)<1e-4,"Sun color not linear radiance");
            require(Arrays.equals(before,values),"Packing changed CPU settings");
            require(read(p,8)==1&&read(p,9)==.5f&&read(p,19)==1,"Default fringes and sky glow must keep the shipped picture");
            float sunFringe=read(p,0)*read(p,8),moonFringe=read(p,4)*read(p,9);
            float[] brighter=values.clone();brighter[SunCelestial.SUN_INTENSITY]=48;brighter[SunCelestial.MOON_INTENSITY]=8;SunCelestial.write(p,brighter,0);
            require(read(p,0)==48&&Math.abs(read(p,0)*read(p,8)-sunFringe)<1e-3&&Math.abs(read(p,4)*read(p,9)-moonFringe)<1e-3&&read(p,19)==1,"Brightness changed the fringe or the sky glow");
            float[] glowing=values.clone();glowing[SunCelestial.SUN_GLOW]=2;SunCelestial.write(p,glowing,0);require(read(p,0)==16&&read(p,8)==2,"Glow changed the disc");
            float[] hidden=values.clone();hidden[SunCelestial.MOON_INTENSITY]=0;SunCelestial.write(p,hidden,0);require(read(p,4)==0&&read(p,9)==0&&read(p,19)==0,"Zero brightness left a moon glow");
            values[SunCelestial.STYLE]=1;values[SunCelestial.SUN_SHAPE]=2;values[SunCelestial.MOON_SHAPE]=1;
            SunCelestial.write(p,values,4);require(read(p,14)==3&&read(p,15)==1&&Math.abs(read(p,10)-(float)Math.PI)<1e-6,"Shapes or new-moon angle");
            SunCelestial.write(p,values,-1);require(Math.abs(read(p,10)-(float)(7*Math.PI/4))<1e-6,"Negative phase wrapping");
            values[SunCelestial.PHASE_ANGLE]=90;values[SunCelestial.PHASE_OFFSET]=90;values[SunCelestial.PHASE_SOFTNESS]=.75f;
            SunCelestial.write(p,values,7);require(Math.abs(read(p,10)-(float)(Math.PI/4))<1e-6,"Phase offset did not wrap through full moon");
            require(Math.abs(read(p,16))<1e-6&&read(p,17)==1&&read(p,18)==.75f,"Rotation cosine/sine or softness");
            values[SunCelestial.PHASE_OFFSET]=360;SunCelestial.write(p,values,4);require(Math.abs(read(p,10)-(float)Math.PI)<1e-6,"360 degree offset changed the phase");
            values[SunCelestial.MOON_PHASES]=0;SunCelestial.write(p,values,4);require(read(p,10)<0,"Always-full moon sentinel");
            values[SunCelestial.SUN_INTENSITY]=0;SunCelestial.write(p,values,4);require(read(p,0)==0&&read(p,1)==0&&read(p,2)==0,"Sun hide option");

            var runtime=new GraphicsSettings(dir);var menu=new MenuSession(runtime,"");menu.checkpoint();
            menu.set("sun.celestialStyle",1);menu.set("sun.celestialSunShape",2);menu.set("sun.celestialMoonShape",1);
            menu.set("sun.celestialSunR",.25f);menu.set("sun.celestialMoonSize",4.5f);
            menu.set("sun.celestialMoonPhaseAngle",135);menu.set("sun.celestialMoonPhaseOffset",45);menu.set("sun.celestialMoonPhaseSoftness",.7f);
            require(!Files.exists(dir.resolve("config")),"Preview wrote to disk");
            var profile=menu.snapshot("celestial-test","Sonne & Mond",GraphicsProfile.Scope.LOOK);
            var decoded=ProfileCodec.decode(ProfileCodec.encode(profile));
            require(decoded.values().get("sun.celestialSunShape")==2&&decoded.values().get("sun.celestialMoonSize")==4.5f,"Portable profile lost appearance");
            require(decoded.values().get("sun.celestialMoonPhaseAngle")==135&&decoded.values().get("sun.celestialMoonPhaseOffset")==45&&Math.abs(decoded.values().get("sun.celestialMoonPhaseSoftness")-.7f)<1e-6,"Profile lost phase controls");
            runtime.commit(runtime.capture());var loaded=new SunTuning(dir);
            require(loaded.value(SunCelestial.SUN_SHAPE)==2&&loaded.value(SunCelestial.MOON_SHAPE)==1&&loaded.value(SunCelestial.MOON_SIZE)==4.5f,"Saved appearance did not reload");
            require(loaded.value(SunCelestial.PHASE_ANGLE)==135&&loaded.value(SunCelestial.PHASE_OFFSET)==45&&Math.abs(loaded.value(SunCelestial.PHASE_SOFTNESS)-.7f)<1e-6,"Phase controls did not reload");
            menu.undo();require(runtime.capture().get("sun.celestialStyle")==0,"Undo did not restore vanilla");
            for(var rule:GraphicsCatalog.RULES.values())if(rule.key().startsWith("sun.celestial"))
                require(GraphicsCatalog.feature(rule).equals("celestial")&&rule.shareable()&&!rule.quality(),"Appearance hidden or treated as quality: "+rule.key());
            try(var in=SunCelestialChecks.class.getResourceAsStream("/net/minecraft/client/renderer/SkyRenderer.class")) {
                require(in!=null,"SkyRenderer bytecode missing");ClassNode node=new ClassNode();new ClassReader(in).accept(node,0);
                require(node.methods.stream().anyMatch(m->m.name.equals("renderSun"))&&node.methods.stream().anyMatch(m->m.name.equals("renderMoon")),"Sprite hook target missing");
            }
            for(String lang:List.of("en_us","de_de"))try(var in=SunCelestialChecks.class.getResourceAsStream("/assets/siliconshaders/lang/"+lang+".json")) {
                var json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
                for(var rule:GraphicsCatalog.RULES.values())if(rule.key().startsWith("sun.celestial"))require(json.has(rule.label())&&json.has(rule.label()+".help"),"Missing "+lang+" translation: "+rule.key());
            }
            System.out.println("PASS: 224-byte celestial packet, stable lighting prefix, linear colors, cycle offset/wrapping, phase rotation/softness, always-full override, shapes, zero brightness, nonmutating preview, profile round trip, persistence, undo, bilingual controls and vanilla sun/moon hook targets");
        } finally {try(var paths=Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
