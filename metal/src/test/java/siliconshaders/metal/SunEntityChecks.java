package siliconshaders.metal;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Real shipped Minecraft GLSL -> SPIR-V -> production Metal translator -> paired world-light adaptation. */
public final class SunEntityChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static String read(String path)throws Exception{try(var in=SunEntityChecks.class.getResourceAsStream(path)){if(in==null)throw new IllegalStateException(path);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private static String expanded(String path)throws Exception{
        String text=read(path);Matcher m=Pattern.compile("(?m)^#include <([^>]+)>$").matcher(text);StringBuilder b=new StringBuilder();
        while(m.find()){Identifier id=Identifier.parse(m.group(1));m.appendReplacement(b,Matcher.quoteReplacement(expanded("/assets/"+id.getNamespace()+"/shaders/include/"+id.getPath())));}m.appendTail(b);return b.toString();
    }
    private static String translated(String glsl,ShaderType type,ShaderDefines defines)throws Exception{
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize(),result=0;
        try{
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_3);
            shaderc_compile_options_set_auto_bind_uniforms(options,true);shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_optimization_level(options,shaderc_optimization_level_zero);
            shaderc_compile_options_add_macro_definition(options,"RENDERPEARL_DEPTH_IS_ZERO_TO_ONE","");
            for(var e:defines.values().entrySet())shaderc_compile_options_add_macro_definition(options,e.getKey(),e.getValue());
            for(var flag:defines.flags())shaderc_compile_options_add_macro_definition(options,flag,"");
            result=shaderc_compile_into_spv(compiler,glsl,type==ShaderType.VERTEX?shaderc_vertex_shader:shaderc_fragment_shader,"entity-check","main",options);
            require(shaderc_result_get_compilation_status(result)==shaderc_compilation_status_success,shaderc_result_get_error_message(result));
            ByteBuffer bytes=shaderc_result_get_bytes(result);
            SpvModule module=new SpvModule(){public void close(){}public ByteBuffer spv(){return bytes;}public ShaderType type(){return type;}public Reflection reflect(){throw new UnsupportedOperationException();}public Reflection getReflectionInfoIfAvailable(){return null;}};
            var translate=MetalPipeline.class.getDeclaredMethod("translateNow",SpvModule.class,int.class);translate.setAccessible(true);
            Object t=translate.invoke(null,module,24);var source=t.getClass().getDeclaredMethod("msl");source.setAccessible(true);return (String)source.invoke(t);
        }finally{if(result!=0)shaderc_result_release(result);shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args.length>0?args[0]:"build/entity-stages");Files.createDirectories(out);
        int checked=0,untouched=0;var rows=new ArrayList<Map<String,Object>>();
        var unique=Collections.newSetFromMap(new IdentityHashMap<RenderPipeline,Boolean>());
        unique.addAll(RenderPipelines.requiredPipelines());unique.addAll(RenderPipelines.optionalPipelines());
        for(var field:RenderPipelines.class.getFields())if(field.getType()==RenderPipeline.class)unique.add((RenderPipeline)field.get(null));
        var pipelines=new ArrayList<>(unique);pipelines.sort(Comparator.comparing(p->p.getLocation().toString()+p.getShaderDefines().asSourceDirectives()));
        for(var p:pipelines){String name=p.getLocation().toString();boolean candidate=SunEntityMsl.candidate(name);
            if(!candidate){if(name.contains("eyes")||name.contains("emissive")||name.contains("energy_swirl")||name.contains("outline")||name.contains("gui"))untouched++;continue;}
            String[] stages=new String[2];int at=0;
            for(var type:new ShaderType[]{ShaderType.VERTEX,ShaderType.FRAGMENT}){
                Identifier id=p.getShaders().get(type);String extension=type==ShaderType.VERTEX?".vsh":".fsh";
                stages[at++]=translated(expanded("/assets/"+id.getNamespace()+"/shaders/"+id.getPath()+extension),type,p.getShaderDefines());
            }
            String stem=String.format(Locale.ROOT,"%02d",checked)+"-"+name.substring(name.lastIndexOf('/')+1);
            Files.writeString(out.resolve(stem+"-vanilla.vs.metal"),stages[0]);Files.writeString(out.resolve(stem+"-vanilla.fs.metal"),stages[1]);
            var pair=SunEntityMsl.patch(name,stages[0],stages[1],BindGroupLayout.flattenUniforms(p.getBindGroupLayouts()).size());
            require(pair!=null,"Known pipeline not adapted: "+name+" "+p.getShaderDefines());
            Files.writeString(out.resolve(stem+".vs.metal"),pair.vertex());Files.writeString(out.resolve(stem+".fs.metal"),pair.fragment());
            require(!pair.fragment().contains("SURFACE_PROFILES"),"Terrain material table leaked into object shader");
            require(pair.fragment().contains("if (siliconshadersLighting.enabled)"),"Vanilla fallback not gated");
            require(!pair.fragment().contains("out.fragColor.a = sun"),"Alpha replaced");
            require(SunEntityMsl.patch(name,stages[0].replace(" UV2 [[attribute("," ChangedUV [[attribute("),stages[1],10)==null,"Mismatched vertex accepted");
            require(SunEntityMsl.patch(name,stages[0],stages[1].replace(" texCoord0 [[user("," ChangedUV [[user("),10)==null,"Mismatched fragment accepted");
            require(SunEntityMsl.patch(name,stages[0],stages[1],25)==null,"Reserved buffer collision accepted");
            rows.add(Map.of("name",name,"stem",stem,"defines",p.getShaderDefines().asSourceDirectives()));checked++;
        }
        require(checked>=15&&untouched>=5,"Too little actual pipeline coverage");
        Files.writeString(out.resolve("manifest.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(rows));
        System.out.println("PASS: "+checked+" real game shader pairs; "+untouched+" special/GUI pipelines excluded; paired fallback and reserved slots");
    }
}
