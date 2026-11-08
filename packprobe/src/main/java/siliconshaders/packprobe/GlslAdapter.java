package siliconshaders.packprobe;

import java.util.ArrayList;
import java.util.regex.Pattern;

/** Deliberately restricted to a legacy full-screen final pass; this is not a terrain or general Iris adapter. */
final class GlslAdapter {
    private GlslAdapter() { }

    static String prepare(String source) {
        if (!source.matches("(?s)^\\s*#version\\s+\\d+.*")) throw new IllegalArgumentException("Missing GLSL version");
        source = source.replaceFirst("#version[^\\n]*", "#version 450 core\n#define MC_VERSION 260300\n#define MC_OS_MAC 1\n#define texture2D texture\n#define texture2DLod textureLod\n");
        return source;
    }

    static String adapt(String source, boolean vertex) {
        source = source.replaceAll("(?m)^\\s*#line[^\\n]*", "");
        var fields = new ArrayList<String>();
        var uniform = Pattern.compile("(?m)^\\s*uniform\\s+(\\w+)\\s+(\\w+)(\\s*\\[[^\\]]+\\])?\\s*(?:=\\s*([^;]+))?;").matcher(source);
        var replaced = new StringBuilder();
        int binding = 1;
        while (uniform.find()) {
            String type = uniform.group(1);
            String replacement;
            if (type.contains("sampler") || type.contains("image")) {
                replacement = "layout(set=0,binding=" + binding++ + ") " + uniform.group().strip();
            } else {
                fields.add(type + " " + uniform.group(2) + (uniform.group(3) == null ? "" : uniform.group(3)) + ";");
                replacement = "";
            }
            uniform.appendReplacement(replaced, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        uniform.appendTail(replaced);
        source = replaced.toString();
        StringBuilder preamble = new StringBuilder();
        if (!fields.isEmpty()) preamble.append("layout(std140,set=0,binding=0) uniform PackUniforms {\n").append(String.join("\n", fields)).append("\n};\n");
        if (vertex) {
            preamble.append("layout(location=0) in vec4 siliconshaders_Position;\nlayout(location=1) in vec4 siliconshaders_TexCoord;\nlayout(location=2) in vec4 siliconshaders_UnsupportedLightmapCoord;\n");
            source = source.replaceAll("\\bftransform\\s*\\(\\s*\\)", "siliconshaders_Position")
                .replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[01]\\s*\\]", "mat4(1.0)")
                .replaceAll("\\bgl_MultiTexCoord0\\b", "siliconshaders_TexCoord")
                .replaceAll("\\bgl_MultiTexCoord1\\b", "siliconshaders_UnsupportedLightmapCoord");
        } else {
            // Exactly one final-pass output. Refuse other legacy output layouts instead of silently losing buffers.
            var output = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]").matcher(source);
            while (output.find()) if (!output.group(1).equals("0")) throw new IllegalArgumentException("Only final-pass output 0 is supported");
            source = source.replaceAll("gl_FragData\\s*\\[\\s*0\\s*\\]", "siliconshaders_FragColor").replaceAll("\\bgl_FragColor\\b", "siliconshaders_FragColor");
            preamble.append("layout(location=0) out vec4 siliconshaders_FragColor;\n");
        }
        int firstLine = source.indexOf('\n');
        return source.substring(0, firstLine + 1) + preamble + source.substring(firstLine + 1);
    }
}
