package siliconshaders.metal;

import com.mojang.renderpearl.api.pipeline.*;
import siliconshaders.metal.sun.SunRain;
import siliconshaders.metal.sun.SunTuning;
import net.minecraft.client.renderer.RenderPipelines;

/** The rain drop pipeline (SunRain) against vanilla's weather pipeline, and its fragment patch (SunRainMsl) on the production translation. */
public final class SunRainChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        var expanded=SunEntityChecks.class.getDeclaredMethod("expanded",String.class);expanded.setAccessible(true);
        var translated=SunEntityChecks.class.getDeclaredMethod("translated",String.class,ShaderType.class,net.minecraft.client.renderer.ShaderDefines.class);translated.setAccessible(true);
        var field=RenderPipelines.class.getDeclaredField("WEATHER_SNIPPET");field.setAccessible(true);
        RenderPipeline vanilla=RenderPipelines.WEATHER,ours=SunRain.build((RenderPipeline.Snippet)field.get(null));
        // Everything but the location is vanilla's: the shaders must be the game's own. The mod's assets are no resource
        // pack, so a shader of ours would not be found in game (it crashed when rain began).
        require(ours.getLocation().equals(SunRain.LOCATION)&&ours.getLocation().toString().equals(SunRainMsl.PIPELINE),"Location");
        require(ours.getShaders().equals(vanilla.getShaders()),"Shaders differ from vanilla's");
        for(var id:ours.getShaders().values())require(id.getNamespace().equals("minecraft"),"Shader the game cannot load: "+id);
        require(ours.getVertexFormatBindings().equals(vanilla.getVertexFormatBindings())&&ours.getPrimitiveTopology()==vanilla.getPrimitiveTopology(),"Vertices differ");
        require(ours.getColorTargetStates().equals(vanilla.getColorTargetStates())&&ours.getDepthStencilState().equals(vanilla.getDepthStencilState()),"Blending or depth differs");
        require(ours.isCull()==vanilla.isCull()&&ours.getShaderDefines().equals(vanilla.getShaderDefines())&&ours.getBindGroupLayouts().equals(vanilla.getBindGroupLayouts()),"State differs");
        // Both stages through shaderc and the production MSL translator; the fragment reads exactly what the vertex stage writes.
        var defines=ours.getShaderDefines();var vertexId=ours.getShaders().get(ShaderType.VERTEX);
        String v=(String)translated.invoke(null,expanded.invoke(null,"/assets/"+vertexId.getNamespace()+"/shaders/"+vertexId.getPath()+".vsh"),ShaderType.VERTEX,defines);
        var fragmentId=ours.getShaders().get(ShaderType.FRAGMENT);
        String vanillaF=(String)translated.invoke(null,expanded.invoke(null,"/assets/"+fragmentId.getNamespace()+"/shaders/"+fragmentId.getPath()+".fsh"),ShaderType.FRAGMENT,defines);
        String f=SunRainMsl.patch(SunRainMsl.PIPELINE,vanillaF);
        require(f!=null,"Rain patch did not match the production translation");
        require(SunRainMsl.patch("minecraft:pipeline/weather",vanillaF)==null&&SunRainMsl.patch("minecraft:pipeline/translucent_particle",vanillaF)==null,"Patch leaks to other pipelines");
        require(SunRainMsl.patch(SunRainMsl.PIPELINE,vanillaF.replace("in.vertexColor","in.otherColor"))==null,"Unfamiliar translation patched");
        require(f.contains("discard_fragment()")&&f.contains("siliconshadersRain ? 0.004 : 0.1")&&f.contains("siliconshadersTexel = float4(1.0, 1.0, 1.0,"),"Rain alpha test or shape missing");
        require(f.replace(" ","").length()>vanillaF.replace(" ","").length()&&f.contains("fragment main0_out main0("),"Patch mangled the stage");
        if(args.length>0){var dir=java.nio.file.Path.of(args[0]);java.nio.file.Files.createDirectories(dir);java.nio.file.Files.writeString(dir.resolve("rain.vs.metal"),v);java.nio.file.Files.writeString(dir.resolve("rain.fs.metal"),f);}
        // Drop color on the CPU: the silver tint and a fifth opacity at the defaults, storm values by thunder, snow untouched.
        int white=net.minecraft.util.ARGB.white(.8f);var tuning=new SunTuning(java.nio.file.Files.createTempDirectory("rain-"));
        var column=SunRain.class.getDeclaredMethod("column",int.class,int.class,float.class);column.setAccessible(true);
        var tint=SunRain.class.getDeclaredMethod("tint",float.class);tint.setAccessible(true);
        int rain=(int)column.invoke(null,white,(int)tint.invoke(null,tuning.value(SunRain.BRIGHTNESS)),tuning.value(SunRain.OPACITY));
        require((rain>>>24)==Math.round(204*.2f)&&(rain>>16&255)==Math.round(.70f*255)&&(rain>>8&255)==Math.round(.75f*255)&&(rain&255)==Math.round(.83f*255),"Default drop color");
        require(((int)tint.invoke(null,1.35f)&255)==255&&((int)tint.invoke(null,0f))==0&&tuning.value(SunRain.STORM_OPACITY)>tuning.value(SunRain.OPACITY),"Drop brightness range or storm defaults");
        require(SunRain.column(white)==white,"Colors changed outside rain columns");
        for(int i:new int[]{SunRain.OPACITY,SunRain.BRIGHTNESS,SunRain.STORM_OPACITY,SunRain.STORM_BRIGHTNESS})require(i>0&&SunTuning.PARAMS.get(i).group()==SunTuning.Group.SKY&&i<SunTuning.MATERIAL_START,"Drop slider "+i);
        require(SunTuning.index("rainSplashes")==SunRain.SPLASHES&&SunTuning.PARAMS.get(SunRain.SPLASHES).group()==SunTuning.Group.SKY&&SunRain.SPLASHES<SunTuning.MATERIAL_START
            &&SunTuning.PARAMS.get(SunRain.SPLASHES).neutral()==0,"Splash setting");
        require(SunTuning.index("weatherDrops")==SunRain.DROPS&&SunTuning.PARAMS.get(SunRain.DROPS).group()==SunTuning.Group.SKY&&SunRain.DROPS<SunTuning.MATERIAL_START,"Drop setting");
        System.out.println("PASS: rain pipeline = vanilla weather (game shaders only) under its own name; fragment patch on the production translation, fallback and scope; drop setting");
    }
}
