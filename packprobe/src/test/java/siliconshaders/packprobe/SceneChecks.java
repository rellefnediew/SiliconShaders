package siliconshaders.packprobe;

public final class SceneChecks {
    public static void run() {
        require(SceneAdapter.uniformDefaults("uniform bool heavyFog=false;\nuniform float cloudHeight=192.0;\n").equals(java.util.Map.of("heavyFog","false","cloudHeight","192.0")),"Uniform initializer defaults were lost");
        var vertex=ShaderCompiler.compileScene("#version 130\nvoid main(){gl_Position=ftransform();}\n","test.vsh");
        require(vertex.buffers().stream().anyMatch(b -> b.get("offsets").toString().contains("siliconshaders_Transform")),"Scene transform missing from reflected uniforms");
        require(vertex.metal().contains("gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5"),"OpenGL to Metal depth-range conversion missing");
        var fragment=ShaderCompiler.compileScene("#version 130\nvoid main(){gl_FragData[0]=vec4(1);gl_FragData[1]=vec4(0);}\n","test.fsh");
        require(fragment.metal().contains("[[color(1)]]"),"Second scene target was lost");
        var custom=ShaderCompiler.compileScene("#version 130\nattribute vec4 mc_Entity;\nattribute vec4 mc_midTexCoord;\nout vec4 result;\nvoid main(){gl_Position=ftransform();result=mc_Entity+mc_midTexCoord;}\n","attributes.vsh");
        @SuppressWarnings("unchecked") var attributes=(java.util.List<java.util.Map<String,Object>>)custom.interfaces().get("inputs");
        require(attributes.stream().map(a -> a.get("location")).distinct().count()==attributes.size(),"Scene attributes have conflicting locations");
        ShaderCompiler.compileScene("#version 130\nuniform sampler2DShadow shadowtex0;\nvoid main(){gl_FragData[0]=gl_Fog.color*shadow2D(shadowtex0,vec3(0.5));}\n","legacy.fsh");
        var compute=ShaderCompiler.compileScene("#version 430\nlayout(local_size_x=1) in;\nvoid main(){}\n","test.csh");
        require(compute.metal().contains("kernel "),"Compute entry point missing");
        require(ShaderCompiler.compileScene("#version 130\n#if 0\nvoid main(){}\n#endif\n","inactive.vsh")==null,"Inactive program not distinguished from compiler failure");
        System.out.println("PASS: scene matrices, multiple fragment targets, compute translation, inactive stages");
    }
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
