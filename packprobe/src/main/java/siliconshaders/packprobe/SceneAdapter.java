package siliconshaders.packprobe;

import java.util.ArrayList;
import java.util.TreeSet;
import java.util.regex.Pattern;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiler experiment for scene programs. Declares required inputs; it never substitutes fake scene data. */
final class SceneAdapter {
    private SceneAdapter() { }

    static java.util.Map<String,String> uniformDefaults(String source) {
        var result=new java.util.TreeMap<String,String>();
        var match=Pattern.compile("(?m)^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(source);
        while(match.find()) result.put(match.group(1),match.group(2).strip());
        return java.util.Map.copyOf(result);
    }

    static String adapt(String source, int stage) {
        source=source.replaceAll("(?m)^\\s*#line[^\\n]*","");
        boolean vertex=stage==shaderc_vertex_shader, fragment=stage==shaderc_fragment_shader;
        StringBuilder declarations=new StringBuilder();
        // Legacy shadow lookups return vec4; modern texture() on a shadow sampler returns float.
        if(source.contains("shadow2D(")) {
            source=source.replaceAll("\\bshadow2D\\s*\\(","siliconshaders_Shadow2D(");
            declarations.append("vec4 siliconshaders_Shadow2D(sampler2DShadow image,vec3 coord){return vec4(texture(image,coord));}\n");
        }
        if(source.contains("shadow2DLod(")) {
            source=source.replaceAll("\\bshadow2DLod\\s*\\(","siliconshaders_Shadow2DLod(");
            declarations.append("vec4 siliconshaders_Shadow2DLod(sampler2DShadow image,vec3 coord,float lod){return vec4(textureLod(image,coord,lod));}\n");
        }
        if(vertex) {
            if(!Pattern.compile("\\b(?:in|attribute)\\s+\\w+\\s+vaPosition\\b").matcher(source).find())
                source=source.replaceAll("\\bvaPosition\\b","gl_Vertex.xyz");
            if(!Pattern.compile("\\b(?:in|attribute)\\s+\\w+\\s+vaNormal\\b").matcher(source).find())
                source=source.replaceAll("\\bvaNormal\\b","gl_Normal");
            String[][] attributes={
                {"gl_Vertex","vec4","siliconshaders_Position"}, {"gl_MultiTexCoord0","vec4","siliconshaders_Uv0"},
                {"gl_MultiTexCoord1","vec4","siliconshaders_Uv1"}, {"gl_Normal","vec3","siliconshaders_Normal"},
                {"gl_Color","vec4","siliconshaders_Color"}, {"gl_MultiTexCoord2","vec4","siliconshaders_Uv2"},
                {"gl_MultiTexCoord3","vec4","siliconshaders_Uv3"}};
            // ftransform needs the position even when the source never mentions gl_Vertex explicitly.
            if(Pattern.compile("\\bftransform\\s*\\(").matcher(source).find()) {
                declarations.append("uniform mat4 siliconshaders_Transform;\n");
                source=source.replaceAll("\\bftransform\\s*\\(\\s*\\)","(siliconshaders_Transform * gl_Vertex)");
            }
            for(int i=0;i<attributes.length;i++) {
                var a=attributes[i]; var pattern=Pattern.compile("\\b"+a[0]+"\\b");
                if(pattern.matcher(source).find()) {
                    declarations.append("layout(location=").append(i).append(") in ").append(a[1]).append(' ').append(a[2]).append(";\n");
                    source=pattern.matcher(source).replaceAll(a[2]);
                }
            }
            for(String[] matrix:new String[][] {
                {"gl_ModelViewProjectionMatrix","mat4"},{"gl_ModelViewMatrixInverse","mat4"},
                {"gl_ProjectionMatrixInverse","mat4"},{"gl_ModelViewMatrix","mat4"},
                {"gl_ProjectionMatrix","mat4"},{"gl_NormalMatrix","mat3"}}) {
                var pattern=Pattern.compile("\\b"+matrix[0]+"\\b");
                if(pattern.matcher(source).find()) {
                    String name="siliconshaders_"+matrix[0].substring(3);
                    source=pattern.matcher(source).replaceAll(name);
                    declarations.append("uniform ").append(matrix[1]).append(' ').append(name).append(";\n");
                }
            }
            if(source.contains("gl_TextureMatrix")) {
                source=source.replaceAll("\\bgl_TextureMatrix\\b","siliconshaders_TextureMatrix");
                declarations.append("uniform mat4 siliconshaders_TextureMatrix[8];\n");
            }
            source=source.replaceAll("\\battribute\\b","in").replaceAll("\\bgl_VertexID\\b","gl_VertexIndex");
            // Assign every additional pack attribute explicitly. Mixing auto-assigned inputs with fixed legacy slots
            // can otherwise produce overlapping SPIR-V locations (mc_Entity and mc_midTexCoord in Complementary).
            var custom=Pattern.compile("(?m)^([ \\t]*)in\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(source);
            StringBuilder mapped=new StringBuilder(); int location=7;
            while(custom.find()) custom.appendReplacement(mapped,java.util.regex.Matcher.quoteReplacement(
                custom.group(1)+"layout(location="+location+++") in "+custom.group(2)+" "+custom.group(3)+";"));
            custom.appendTail(mapped); source=mapped.toString();
        }
        for(String[] fog:new String[][]{{"color","vec4"},{"density","float"},{"start","float"},{"end","float"},{"scale","float"}}) {
            var property=Pattern.compile("\\bgl_Fog\\s*\\.\\s*"+fog[0]+"\\b");
            if(property.matcher(source).find()) {
                String name="siliconshaders_Fog_"+fog[0];
                source=property.matcher(source).replaceAll(name);
                declarations.append("uniform ").append(fog[1]).append(' ').append(name).append(";\n");
            }
        }
        // Core-profile matrix names are supplied by a shader loader rather than declared by the pack.
        for(String name:new String[]{"projectionMatrix","modelViewMatrix"}) {
            if(Pattern.compile("\\b"+name+"\\b").matcher(source).find()
                && !Pattern.compile("\\b(?:uniform\\s+)?mat4\\s+"+name+"\\b").matcher(source).find())
                declarations.append("uniform mat4 ").append(name).append(";\n");
        }
        if(fragment) {
            var outputs=new TreeSet<Integer>();
            var matcher=Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]").matcher(source);
            while(matcher.find()) outputs.add(Integer.parseInt(matcher.group(1)));
            if(source.contains("gl_FragColor")) outputs.add(0);
            for(int output:outputs) {
                if(output>7) throw new IllegalArgumentException("More than eight fragment color outputs");
                String name="siliconshaders_FragColor"+output;
                source=source.replaceAll("gl_FragData\\s*\\[\\s*"+output+"\\s*\\]",name);
                if(output==0) source=source.replaceAll("\\bgl_FragColor\\b",name);
                declarations.append("layout(location=").append(output).append(") out vec4 ").append(name).append(";\n");
            }
        }
        if(vertex || fragment) source=source.replaceAll("\\bvarying\\b",vertex?"out":"in");
        int line=source.indexOf('\n');
        source=source.substring(0,line+1)+declarations+source.substring(line+1);
        var fields=new ArrayList<String>();
        var uniforms=Pattern.compile("(?m)^\\s*((?:(?:readonly|writeonly|coherent|restrict|volatile)\\s+)*)uniform\\s+(\\w+)\\s+(\\w+)(\\s*\\[[^\\]]+\\])?\\s*(?:=\\s*([^;]+))?;").matcher(source);
        StringBuilder replaced=new StringBuilder(); int binding=1;
        while(uniforms.find()) {
            String type=uniforms.group(2),name=uniforms.group(3),array=uniforms.group(4)==null?"":uniforms.group(4);
            String replacement;
            if(type.contains("sampler") || type.contains("image")) {
                replacement="layout(set=0,binding="+binding+++") "+uniforms.group().strip();
            } else {
                fields.add(type+" "+name+array+";"); replacement="";
            }
            uniforms.appendReplacement(replaced,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        uniforms.appendTail(replaced); source=replaced.toString();
        if(!fields.isEmpty()) {
            line=source.indexOf('\n');
            source=source.substring(0,line+1)+"layout(std140,set=0,binding=0) uniform PackUniforms {\n"+String.join("\n",fields)+"\n};\n"+source.substring(line+1);
        }
        return source;
    }
}
