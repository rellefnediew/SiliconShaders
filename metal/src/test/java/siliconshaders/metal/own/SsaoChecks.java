package siliconshaders.metal.own;

import java.nio.file.Files;
import java.nio.file.Path;
import siliconshaders.metal.sun.SunTuning;
import org.objectweb.asm.*;

/** Configuration compatibility and the actual game's opaque/object/transparency ordering. No game launch. */
public final class SsaoChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("ssao-checks-");
        try {
            Files.createDirectories(directory.resolve("config"));
            Files.writeString(directory.resolve("config/siliconshaders-sun-tuning.json"),"{\"waterAmplitude\":2,\"shadowContactFix\":0}");
            var tuning=new SunTuning(directory);
            int on=SunTuning.index("ssao"),strength=SunTuning.index("ssaoStrength"),radius=SunTuning.index("ssaoRadius"),quality=SunTuning.index("ssaoQuality");
            require(SunTuning.LIGHTING_COUNT==76&&SunTuning.WATER_START==76,"SSAO changed existing shader packet offsets");
            require(on>SunTuning.SHADOW_CONTACT_FIX,"SSAO was inserted into an existing packet");
            require(tuning.value(on)==1&&tuning.value(strength)==.65f&&tuning.value(radius)==1.25f&&tuning.value(quality)==1,"Old configuration did not acquire SSAO defaults");
            tuning.set(on,0);tuning.set(radius,2);tuning.set(quality,2);
            var loaded=new SunTuning(directory);
            require(loaded.value(on)==0&&loaded.value(radius)==2&&loaded.value(quality)==2,"SSAO settings did not persist");
            require(loaded.value(SunTuning.WATER_START)==2&&loaded.value(SunTuning.SHADOW_CONTACT_FIX)==0,"SSAO write changed water/shadow choices");
        }finally{try(var paths=Files.walk(directory)){for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}
        boolean[] order={false,false,false};int[] calls={0,0};
        try(var input=SsaoChecks.class.getClassLoader().getResourceAsStream("net/minecraft/client/renderer/LevelRenderer.class")){
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] exceptions){
                    return new MethodVisitor(Opcodes.ASM9){
                        boolean solid;
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){
                            if(method.equals("executeSolid")){
                                if(name.equals("renderGroup"))solid=true;
                                if(name.equals("executeSolid")&&owner.endsWith("$PreparedFrame")){require(solid,"Opaque objects precede terrain preparation");order[0]=true;}
                            }
                            if(method.startsWith("lambda$")){
                                if(name.equals("executeSolid")&&owner.endsWith("/LevelRenderer"))solid=true;
                                if(name.equals("executeClassicTransparency")){require(solid,"SSAO hook no longer follows opaque objects");order[1]=true;}
                            }
                            if(method.equals("executeClassicTransparency")){
                                if(name.equals("executeTranslucent")){calls[0]++;order[2]=true;}
                                if(name.equals("renderGroup"))calls[1]++;
                            }
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        require(order[0]&&order[1]&&order[2]&&calls[0]==1&&calls[1]==1,"Game changed SSAO's depth/transparent ordering");
        boolean[] hook={false};
        try(var input=SsaoChecks.class.getClassLoader().getResourceAsStream("siliconshaders/metal/mixin/own/OwnLevelRendererMixin.class")){
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions){
                    if(!name.equals("siliconshaders$ssao"))return null;
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public AnnotationVisitor visitAnnotation(String desc,boolean visible){
                            if(!desc.endsWith("/Inject;"))return null;
                            return new AnnotationVisitor(Opcodes.ASM9){
                                boolean method,head;
                                @Override public AnnotationVisitor visitArray(String key){return new AnnotationVisitor(Opcodes.ASM9){
                                    @Override public void visit(String k,Object value){if(key.equals("method")&&value.equals("executeClassicTransparency"))method=true;}
                                    @Override public AnnotationVisitor visitAnnotation(String k,String desc){return new AnnotationVisitor(Opcodes.ASM9){
                                        @Override public void visit(String k,Object value){if(k.equals("value")&&value.equals("HEAD"))head=true;}
                                    };}
                                };}
                                @Override public void visitEnd(){hook[0]=method&&head;}
                            };
                        }
                    };
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        require(hook[0],"SSAO must run before translucent features and terrain");
        System.out.println("SSAO settings and actual Minecraft opaque/entity/transparency hook checks passed");
    }
}
