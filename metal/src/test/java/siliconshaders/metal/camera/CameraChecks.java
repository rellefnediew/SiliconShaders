package siliconshaders.metal.camera;

import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Persistent user controls and actual camera-space reprojection, without opening Minecraft. */
public final class CameraChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("siliconshaders-camera-check");
        try{
            CameraSettings s=new CameraSettings(dir);require(s.mask()==0,"New install runs optional post passes");
            int[] toggles={1,8,17,22,32,36,44,48};
            for(int i=0;i<toggles.length;i++){
                s.reset(null);s.set(toggles[i],1);require(s.mask()==1<<i,"Effect does not independently enable its path");
                CameraSettings reloaded=new CameraSettings(dir);require(Arrays.equals(s.snapshot(),reloaded.snapshot()),"Controls did not persist");
                s.set(0,0);require(s.mask()==0,"Master switch did not disable all work");
            }
            require(CameraSettings.COUNT<=59&&CameraSettings.index("godrays")==48&&CameraSettings.index("raysUnderwater")==56&&CameraSettings.index("raysVariant")==57,"Camera controls overlap runtime packet slots");
            s.reset(null);require(CameraSettings.index("bloomVariant")==58&&!CameraSettings.PARAMS.get(58).toggle(),"Bloom selector overlaps runtime data or acts as toggle");
            require(!s.hdrBloom()&&s.value(58)==0,"HDR bloom changed old defaults");
            s.set(1,1);s.set(58,1);require(s.mask()==1&&s.hdrBloom(),"HDR selector did not preserve the bloom mask");
            require(new CameraSettings(dir).value(58)==1,"Bloom variant did not persist");
            s.set(2,0);require(!s.hdrBloom(),"Zero-strength bloom still routes HDR");s.set(2,.3f);s.set(0,0);require(!s.hdrBloom(),"Camera master Off still routes HDR");
            s.set(0,1);s.set(22,1);s.reset(CameraSettings.Group.BLOOM);require(s.value(58)==0&&s.value(22)==1&&!s.hdrBloom(),"Bloom reset changed other effects or retained HDR");
            s.reset(null);require(s.value(57)==0,"Original Godrays are not the safe default");
            s.set(48,1);s.set(52,32);s.set(57,1);require(s.mask()==128&&s.value(52)==32,"Variant switch changed quality or selected extra effects");
            require(new CameraSettings(dir).value(57)==1,"Godrays variant did not persist");
            s.reset(CameraSettings.Group.GODRAYS);require(s.value(57)==0,"Godrays group reset did not restore Original");
            s.reset(null);s.set(48,1);s.set(49,0);require(s.mask()==0,"Zero-strength rays still select a GPU path");
            s.set(36,1);s.set(49,.75f);s.reset(CameraSettings.Group.GODRAYS);require(s.value(48)==0&&s.value(36)==1,"Godrays reset changed another group");
            s.reset(null);s.set(1,1);s.set(22,1);s.set(23,.67f);s.reset(CameraSettings.Group.BLOOM);
            require(s.value(1)==0&&s.value(22)==1&&Math.abs(s.value(23)-.67f)<.001,"Group reset changed another effect");
            s.set(23,Float.NaN);require(Math.abs(s.value(23)-.67f)<.001,"NaN setting accepted");s.set(23,100);require(s.value(23)==2,"Out-of-range setting not clamped");
            byte[] persisted=Files.readAllBytes(dir.resolve("config/siliconshaders-camera.json"));float[] own=s.snapshot();s.benchmark(CameraSettings.defaults());require(s.mask()==0,"Benchmark standard kept user effects active");s.benchmark(own);require(Arrays.equals(persisted,Files.readAllBytes(dir.resolve("config/siliconshaders-camera.json"))),"Benchmark rewrote user settings");
            float[] snapshot=s.snapshot();snapshot[0]=0;require(s.value(0)==1,"Published settings externally mutable");
            Files.writeString(dir.resolve("config/siliconshaders-camera.json"),"{\"bloom\":1,\"bloomStrength\":0.5,\"futureControl\":9}");s=new CameraSettings(dir);
            require(s.mask()==1&&s.value(9)==1&&s.value(22)==0&&s.value(57)==0&&s.value(58)==0,"New controls did not migrate from defaults");
            Files.writeString(dir.resolve("config/siliconshaders-camera.json"),"{\"bloom\":1,\"bloomStrength\":\"NaN\"}");s=new CameraSettings(dir);require(s.mask()==0&&!s.issue().isEmpty(),"Corrupt file activated partial effects");
            var history=new CameraHistory();var projection=new Matrix4f().perspective(1.2f,1.6f,.05f,512,true);Object world=new Object();long now=1_000_000_000L;
            require(!history.update(projection,1e8,64,-1e8,world,1920,1200,now,false),"First frame reused stale history");
            require(history.update(projection,1e8,64,-1e8,world,1920,1200,now+=16_000_000,false)&&!history.moving(),"Static view manufactured motion");
            require(history.update(projection,1e8+.25,64,-1e8,world,1920,1200,now+=16_000_000,false)&&history.moving(),"Real camera translation lost history");
            Vector4f p=new Vector4f(0,0,-8,1),actual=history.reprojection().transform(new Vector4f(p)),expected=projection.transform(new Vector4f(.25f,0,-8,1));
            require(actual.distance(expected)<1e-5,"Camera-relative translation has wrong sign or loses large-coordinate precision");
            require(!history.update(projection,1e8+100,64,-1e8,world,1920,1200,now+=16_000_000,false),"Teleport blurred the whole screen");
            require(!history.update(projection,1e8+100,64,-1e8,world,1280,800,now+=16_000_000,false),"Viewport resize retained motion");
            require(!history.update(new Matrix4f().perspective(.8f,1.6f,.05f,512,true),1e8+100,64,-1e8,world,1280,800,now+=16_000_000,false),"FOV change retained motion");
            require(!history.update(projection,0,0,0,new Object(),1280,800,now+=16_000_000,false),"New world reused camera history");
            require(!history.update(projection,0,0,0,world,1280,800,now+=16_000_000,true),"Paused camera produced blur");
            require(!history.update(new Matrix4f().zero(),0,0,0,world,1280,800,now+=16_000_000,false),"Singular camera accepted");
            history.reset();require(!history.moving(),"Reset retained velocity");
            // Every visible control has labels/help in both shipped languages.
            for(String lang:new String[]{"de_de","en_us"}){
                try(var in=CameraChecks.class.getResourceAsStream("/assets/siliconshaders/lang/"+lang+".json")){
                    var labels=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(Objects.requireNonNull(in))).getAsJsonObject();
                    for(var param:CameraSettings.PARAMS)require(labels.has("siliconshaders.camera."+param.key())&&labels.has("siliconshaders.camera."+param.key()+".help"),"Missing translated control: "+param.key());
                }
            }
            var calls=new ArrayList<String>();
            try(var in=CameraChecks.class.getResourceAsStream("/siliconshaders/metal/mixin/pack/WaterGameRendererMixin.class")){
                new org.objectweb.asm.ClassReader(Objects.requireNonNull(in)).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9){
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                        if(!name.equals("siliconshaders$waterBeforeHud"))return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9){@Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){if(name.equals("finish")&&(owner.endsWith("WaterRuntime")||owner.endsWith("CameraRuntime")))calls.add(owner);}};
                    }
                },0);
            }
            require(calls.equals(List.of("siliconshaders/metal/own/WaterRuntime","siliconshaders/metal/camera/CameraRuntime")),"Camera post is not ordered after water in the before-hand hook");
            System.out.println("PASS: eight independent effects, safe defaults, persistent/clamped/migrated settings, group resets, bilingual labels, static/moving camera reprojection, large worlds, teleport/FOV/resize/pause/world reset");
        }finally{try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
