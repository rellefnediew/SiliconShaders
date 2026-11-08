package siliconshaders.metal.own;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class WaterChecks {
    static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args)throws Exception {
        for(int block=0;block<16;block++)for(int sky=0;sky<16;sky++) {
            int original=block*16|(sky*16<<16),encoded=WaterVertices.encode(original);
            require((encoded&65535)==255,"Water marker not distinct from valid light levels");
            require(WaterVertices.decode(encoded)==original,"Water metadata changed block/sky lighting");
        }
        Sink sink=new Sink();boolean[] water={true};WaterVertices vertices=new WaterVertices(sink,()->water[0]);
        vertices.addVertex(1,2,3,0xffabcd12,.2f,.4f,0,0x00a00030,0,1,0);
        require(sink.light==WaterVertices.encode(0x00a00030)&&sink.x==1&&sink.color==0xffabcd12,"Full fluid vertex changed geometry/color or lost water");
        vertices.addVertex(4,5,6).setColor(-1).setUv(.1f,.5f).setUv2(32,224).setNormal(0,1,0);
        require(sink.light==WaterVertices.encode(32|(224<<16)),"Chained fluid vertex lost water");
        water[0]=false;vertices.setUv2(48,160);require(sink.light==0x00a00030,"Glass/lava acquired water metadata");
        int[] material={2};WaterVertices tagged=new WaterVertices(sink,()->water[0],()->material[0]);
        tagged.setUv2(48,160);require(sink.light==WaterVertices.encodeMaterial(0x00a00030,2),"Chained transparent vertex lost identity");
        material[0]=6;tagged.addVertex(1,2,3,0xffabcd12,.2f,.4f,0,0x00a00030,0,1,0);
        require(sink.light==WaterVertices.encodeMaterial(0x00a00030,6)&&sink.color==0xffabcd12,"Full transparent vertex changed tint or lost identity");
        water[0]=true;tagged.setUv2(48,160);require(sink.light==WaterVertices.encode(0x00a00030),"Waterlogged block mixed water and material metadata");water[0]=false;
        WaterRuntime.Submersion state=new WaterRuntime.Submersion();long now=1_000_000_000;
        state.update(now,false,false);require(state.wet==0,"Dry view starts wet");
        state.update(now+=16_666_667,false,true);require(state.wet==1&&state.pulse>.9,"Entry transition missing");
        float pulse=state.pulse;state.update(now+=100_000_000,true,true);require(state.wet==1&&state.pulse==pulse,"Pause advanced entry transition");
        state.update(now+=16_666_667,false,false);require(state.wet>0&&state.wet<1,"Exit did not retain a decaying wet lens");
        for(int i=0;i<400;i++)state.update(now+=16_666_667,false,false);
        require(state.wet<.002&&state.pulse<.002,"Exit effect did not stop");state.reset();require(state.wet==0,"World reset retained lens effects");
        java.nio.file.Path temporary=java.nio.file.Files.createTempDirectory("water-settings-");
        try {
            var tuning=new siliconshaders.metal.sun.SunTuning(temporary);
            int start=siliconshaders.metal.sun.SunTuning.WATER_START;
            require(start==siliconshaders.metal.sun.SunTuning.LIGHTING_COUNT,"Water changed the lighting packet ABI");
            tuning.set(start,2.5f);tuning.set(start+1,0);tuning.set(start+6,100);
            tuning.set(siliconshaders.metal.sun.SunTuning.HAND_INTENSITY,1.5f);
            var loaded=new siliconshaders.metal.sun.SunTuning(temporary);
            require(loaded.value(start)==2.5f&&loaded.value(start+1)==0&&loaded.value(start+6)==100,"Water settings did not persist");
            loaded.reset(siliconshaders.metal.sun.SunTuning.Group.WATER);
            require(loaded.value(start)==1&&loaded.value(start+6)==64,"Water reset did not restore defaults");
            require(loaded.value(siliconshaders.metal.sun.SunTuning.HAND_INTENSITY)==1.5f,"Water reset changed hand lighting");
        } finally {
            try(var paths=java.nio.file.Files.walk(temporary)) {
                for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);
            }
        }
        // Vanilla's water vision changes with time. Native tint must remain the biome tint.
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            long address=stack.ncalloc(16,1,16);
            var vanillaFog=new net.minecraft.client.renderer.fog.FogData();
            WaterRuntime.captureFog(new org.joml.Vector3f(.02f,.08f,.20f));
            for(int tick=0;tick<=600;tick++) {
                float adaptation=tick/600f;
                vanillaFog.color.set(.02f+.08f*adaptation,.08f+.32f*adaptation,.2f+.8f*adaptation,1);
                WaterRuntime.writeFogColor(address,vanillaFog,true);
                require(org.lwjgl.system.MemoryUtil.memGetFloat(address)==.02f
                    &&org.lwjgl.system.MemoryUtil.memGetFloat(address+4)==.08f
                    &&org.lwjgl.system.MemoryUtil.memGetFloat(address+8)==.20f,"Vanilla adaptation progressively changed native water tint");
            }
            WaterRuntime.writeFogColor(address,vanillaFog,false);
            require(org.lwjgl.system.MemoryUtil.memGetFloat(address+8)==vanillaFog.color.z,"Water tint leaked into the dry view");
            WaterRuntime.close();
        }
        int[] worldOrder={0,0,0,0};String[] hudTarget={null};
        try(InputStream input=WaterChecks.class.getClassLoader().getResourceAsStream("net/minecraft/client/renderer/GameRenderer.class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                    if(!method.equals("renderLevel")&&!method.equals("render3dHud"))return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf) {
                            if(method.equals("renderLevel")) {
                                if(owner.equals("net/minecraft/client/renderer/LevelRenderer")&&name.equals("render"))worldOrder[0]++;
                                if(owner.equals("net/minecraft/client/renderer/GameRenderer")&&name.equals("render3dHud")) {
                                    require(worldOrder[0]==1,"Water hook would precede the world render");worldOrder[1]++;
                                    hudTarget[0]="L"+owner+";"+name+descriptor;
                                }
                            } else {
                                if(name.equals("clearDepthTexture"))worldOrder[2]++;
                                if(name.equals("renderItemInHand")){require(worldOrder[2]==1,"Expected hand depth clear missing");worldOrder[3]++;}
                            }
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        boolean[] hook={false};
        // Read the injection annotation without initializing Minecraft or its renderer.
        try(InputStream input=WaterChecks.class.getClassLoader().getResourceAsStream("siliconshaders/metal/mixin/pack/WaterGameRendererMixin.class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!name.equals("siliconshaders$waterBeforeHud"))return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor,boolean visible) {
                            if(!descriptor.endsWith("/Inject;"))return null;
                            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                boolean level,before,target;
                                @Override public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                                    return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String key,Object value){if(name.equals("method")&&value.equals("renderLevel"))level=true;}
                                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String key,String desc) {
                                            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                                @Override public void visit(String key,Object value){if(key.equals("target")&&value.equals(hudTarget[0]))target=true;}
                                                @Override public void visitEnum(String key,String desc,String value){if(key.equals("shift")&&value.equals("BEFORE"))before=true;}
                                            };
                                        }
                                    };
                                }
                                @Override public void visitEnd(){hook[0]=level&&before&&target;}
                            };
                        }
                    };
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        require(worldOrder[0]==1&&worldOrder[1]==1&&worldOrder[2]==1&&worldOrder[3]==1&&hook[0],"Water must compose after the world, before the hand clears world depth");
        // Verify hook targets and HUD order in the actual Minecraft bytecode. No game/UI initialization.
        int[] calls={0,0};boolean[] fog={false};
        try(InputStream input=WaterChecks.class.getClassLoader().getResourceAsStream("net/minecraft/client/renderer/GameRenderer.class")) {
            require(input!=null,"Minecraft renderer bytecode missing");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!name.equals("render")||!descriptor.equals("()V"))return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf) {
                            if(owner.equals("net/minecraft/client/renderer/GameRenderer")&&name.equals("applyPostEffects"))calls[0]++;
                            if(owner.equals("net/minecraft/client/gui/render/GuiRenderer")&&name.equals("render")){require(calls[0]==1,"Water hook would affect HUD order");calls[1]++;}
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        try(InputStream input=WaterChecks.class.getClassLoader().getResourceAsStream("net/minecraft/client/renderer/fog/FogRenderer.class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(name.equals("setupFog")&&descriptor.equals("(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;"))fog[0]=true;
                    return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        require(calls[0]==1&&calls[1]==1&&fog[0],"Water/fog injection target missing");
        try(InputStream input=WaterChecks.class.getClassLoader().getResourceAsStream("siliconshaders-pack.mixins.json")) {
            String json=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            require(json.contains("\"WaterGameRendererMixin\"")&&json.contains("\"WaterFogMixin\"")&&!json.contains("\"GameRendererMixin\""),"Water hook missing or obsolete shaderpack hook activated");
        }
        System.out.println("PASS: all 256 water light combinations, fluid/glass classification, both vertex APIs, entry/pause/exit/reset, water tuning persistence/reset, stable biome tint, world-before-hand-depth-clear and fog hook targets");
    }
    private static final class Sink implements VertexConsumer {
        int light,color;float x;
        @Override public void addVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz) {this.x=x;this.color=color;this.light=light;}
        @Override public VertexConsumer addVertex(float x,float y,float z){this.x=x;return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        @Override public VertexConsumer setColor(int value){color=value;return this;}
        @Override public VertexConsumer setUv(float u,float v){return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){light=u|(v<<16);return this;}
        @Override public VertexConsumer setUv3(float u,float v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
        @Override public VertexConsumer setLineWidth(float width){return this;}
    }
}
