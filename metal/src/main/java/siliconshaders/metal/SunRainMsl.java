package siliconshaders.metal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rain drops in our weather pipeline only (siliconshaders.metal.sun.SunRain): vanilla's particle fragment stage as translated, except that
 * a rain texel gives only the streak's shape. The texture is one blue with its alpha as the streak, so the color goes and the
 * column's own (SunRain's tint, times vanilla's lightmap) stands alone. The alpha is lifted at its low end, which draws the streaks'
 * tails out at low opacity. Only empty texels are dropped. Snow (the white texture) is vanilla's. Patched here, not shipped as a shader
 * resource: the mod's assets are not a resource pack (no Fabric resource loader), so the game's shader manager never sees them.
 * An unfamiliar translation stays vanilla.
 */
final class SunRainMsl {
    static final String PIPELINE="siliconshaders:pipeline/weather";
    private static final Pattern COLOR=Pattern.compile(
        "float4 (\\w+) = \\(Sampler0\\.sample\\(Sampler0Smplr, in\\.texCoord0\\) \\* in\\.vertexColor\\) \\* (_\\w+)\\.ColorModulator;\\n"
        +"(\\s*)if \\(\\1\\.w < ([0-9.]+)\\)\\n\\s*\\{\\n\\s*discard_fragment\\(\\);\\n\\s*\\}\\n");
    private SunRainMsl() {}

    /** The patched fragment stage, or null (vanilla's) for other pipelines or an unexpected translation. */
    static String patch(String name,String fragment) {
        if(!PIPELINE.equals(name))return null;
        Matcher m=COLOR.matcher(fragment);
        if(!m.find()||m.find())return null;
        m.reset();m.find();
        String c=m.group(1),indent=m.group(3);
        String drops=indent+"float4 siliconshadersTexel = Sampler0.sample(Sampler0Smplr, in.texCoord0);\n"
            // The rain texture is blue, the snow texture has no color at all.
            +indent+"bool siliconshadersRain = siliconshadersTexel.z > siliconshadersTexel.x + 0.25;\n"
            +indent+"if (siliconshadersRain)\n"+indent+"{\n"
            +indent+"    siliconshadersTexel = float4(1.0, 1.0, 1.0, siliconshadersTexel.w * (2.0 - siliconshadersTexel.w));\n"
            +indent+"}\n"
            +indent+"float4 "+c+" = (siliconshadersTexel * in.vertexColor) * "+m.group(2)+".ColorModulator;\n"
            +indent+"if ("+c+".w < (siliconshadersRain ? 0.004 : "+m.group(4)+"))\n"+indent+"{\n"+indent+"    discard_fragment();\n"+indent+"}\n";
        return fragment.substring(0,m.start())+drops.substring(indent.length())+fragment.substring(m.end());
    }
}
