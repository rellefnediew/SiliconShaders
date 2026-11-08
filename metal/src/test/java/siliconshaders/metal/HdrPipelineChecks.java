package siliconshaders.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.*;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Actual frontend validation and Metal PSOs, including the format mismatch that the native-only bloom test missed. */
public final class HdrPipelineChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static SpvModule shader(String glsl,ShaderType type){
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize(),result=0;
        try {
            result=shaderc_compile_into_spv(compiler,glsl,type==ShaderType.VERTEX?shaderc_vertex_shader:shaderc_fragment_shader,"hdr-probe","main",options);
            require(shaderc_result_get_compilation_status(result)==shaderc_compilation_status_success,shaderc_result_get_error_message(result));
            ByteBuffer bytes=MemoryUtil.memAlloc(Objects.requireNonNull(shaderc_result_get_bytes(result)).remaining());bytes.put(shaderc_result_get_bytes(result)).flip();
            return new SpvModule(){public void close(){MemoryUtil.memFree(bytes);}public ByteBuffer spv(){return bytes;}public ShaderType type(){return type;}public Reflection reflect(){throw new UnsupportedOperationException();}public Reflection getReflectionInfoIfAvailable(){return null;}};
        } finally {if(result!=0)shaderc_result_release(result);shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }
    private static float red(int packed){int v=packed&2047,exponent=v>>>6,mantissa=v&63;return exponent==0?Math.scalb((float)mantissa,-20):Math.scalb(1+mantissa/64f,exponent-15);}
    public static void main(String[] args)throws Exception {
        var backend=new MetalDevice();var device=new FrontendGpuDevice(backend);var commands=device.createCommandEncoder();var encoder=(MetalEncoder)backend.createCommandEncoder();
        var vertex=shader("#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0.5,1);}",ShaderType.VERTEX);
        var fragment=shader("#version 450\nlayout(location=0)out vec4 color;void main(){color=vec4(4,2,1,0.5);}",ShaderType.FRAGMENT);
        var originals=new ArrayList<FrontendRenderPipeline>();int checked=0;
        try {
            for(boolean depth:new boolean[]{false,true})for(int blend=0;blend<3;blend++) {
                var target=new ColorTargetState(blend==0?Optional.empty():Optional.of(blend==1?BlendFunction.TRANSLUCENT:BlendFunction.ADDITIVE),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL);
                var info=new CreateInfo("hdr-probe-"+depth+"-"+blend,List.of(new CreateInfo.Shader("vertex","main",vertex),new CreateInfo.Shader("fragment","main",fragment)),List.of(),List.of(),List.of(),0,depth?new DepthStencilState(CompareOp.ALWAYS_PASS,true):null,PolygonMode.FILL,false,List.of(target),PrimitiveTopology.TRIANGLES);
                var metal=MetalPipeline.compile(encoder,info);require(metal!=null,"Compile failed");
                originals.add(new FrontendRenderPipeline(info.name(),metal,List.of(),new Object2IntOpenHashMap<>(),List.of(),List.of(target),depth,0));
            }
            // Variant creation must not access released SPIR-V; it reuses the already translated, patched MSL.
            vertex.close();fragment.close();vertex=fragment=null;
            for(var original:originals) {
                var hdr=(FrontendRenderPipeline)MetalBridge.hdrPipeline(original);
                require(hdr!=original&&hdr.backendRenderPipeline()!=original.backendRenderPipeline(),"Only frontend metadata changed");
                require(MetalBridge.hdrPipeline(original)==hdr&&MetalBridge.hdrPipeline(hdr)==hdr,"HDR cache/format guard failed");
                require(hdr.uniforms()==original.uniforms()&&hdr.vertexFormats()==original.vertexFormats()&&hdr.pushConstantSize()==original.pushConstantSize(),"HDR changed vertex/uniform layout");
                var a=original.colorTargetStates().getFirst();var b=hdr.colorTargetStates().getFirst();require(a.blendFunction().equals(b.blendFunction())&&a.writeMask()==b.writeMask()&&b.format()==GpuFormat.RG11B10_FLOAT,"Blend/write state changed");
                for(int size:new int[]{32,65})for(int frame=0;frame<6;frame++) {
                    boolean useHdr=(frame&1)==0;int usage=GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC;
                    try(var color=device.createTexture("HDR switch probe",usage,useHdr?GpuFormat.RG11B10_FLOAT:GpuFormat.RGBA8_UNORM,size,size,1,1);var cv=device.createTextureView(color);
                        var depth=device.createTexture("Depth",GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.D32_FLOAT,size,size,1,1);var dv=device.createTextureView(depth)) {
                        try(var pass=original.wantsDepthTexture()?commands.createRenderPass(()->"HDR frontend",cv,Optional.of(new Vector4f()),dv,OptionalDouble.of(1)):commands.createRenderPass(()->"HDR frontend",cv,Optional.of(new Vector4f()))) {
                            if(useHdr) {
                                boolean rejected=false;try{pass.setPipeline(original);}catch(IllegalStateException expected){rejected=expected.getMessage().contains("format");}
                                require(rejected,"Unfixed frontend did not reproduce reported crash");
                            }
                            pass.setPipeline(useHdr?hdr:original);pass.draw(3,1,0,0);
                        }
                        commands.submit();encoder.waitIdle();
                        long storage=MetalBridge.newBuffer(encoder.ctx,size*size*4L);
                        try {
                            MetalBridge.readTexture(encoder.enc,MetalBridge.textureHandle(color),size,size,4,storage);MetalBridge.commitAndWait(encoder.enc);
                            int value=MemoryUtil.memGetInt(MetalBridge.bufferContents(storage));
                            require(useHdr?red(value)>1.9f:(value&255)==(a.blendFunction().equals(Optional.of(BlendFunction.TRANSLUCENT))?128:255),"HDR emitter energy clipped / Original changed: "+original.name()+" hdr="+useHdr+" packed="+Integer.toHexString(value)+" red="+red(value));
                        } finally {Native.release(storage);}
                        checked++;
                    }
                    require(MetalBridge.hdrPipeline(original)==hdr,"Resize/toggle recompiled HDR state");
                }
                original.close();require(hdr.isClosed(),"Original pipeline did not release owned HDR variant");original.close();
            }
            System.out.println("PASS: "+checked+" actual frontend/Metal HDR draws: reproduced old format exception, >1 HDR energy, Original/HDR switches, resize, solid/alpha/additive, depth/no-depth, cached variants, original-owned disposal");
        } finally {if(vertex!=null)vertex.close();if(fragment!=null)fragment.close();for(var p:originals)p.close();backend.close();}
    }
}
