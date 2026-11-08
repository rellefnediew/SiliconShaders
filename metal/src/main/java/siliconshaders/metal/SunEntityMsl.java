package siliconshaders.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapt only known world-object stages. A pair is accepted atomically; unfamiliar shaders stay vanilla. */
final class SunEntityMsl {
    record Pair(String vertex,String fragment) {}
    private static final Set<String> NAMES=Set.of(
        "entity_solid","entity_solid_offset_forward","entity_cutout","entity_cutout_cull",
        "entity_cutout_z_offset","entity_cutout_dissolve","entity_translucent","entity_translucent_cull",
        "armor_cutout_no_cull","armor_decal_cutout_no_cull","wolf_armor_cracks","banner_pattern",
        "item_cutout","item_translucent","solid_block","cutout_block","translucent_block");
    private static final Set<String> LOGGED=ConcurrentHashMap.newKeySet();
    private static final Pattern PROJECT=Pattern.compile("out\\.gl_Position = \\(_\\w+\\.ProjMat \\* _\\w+\\.ModelViewMat\\) \\* float4\\(([\\w.]+(?: \\+ _\\w+\\.ModelOffset)?), 1\\.0\\);");
    private static final Pattern DT=Pattern.compile("constant DynamicTransforms& (\\w+) \\[\\[buffer\\(\\d+\\)\\]\\]");
    private static final Pattern FOG=Pattern.compile("constant Fog& (\\w+) \\[\\[buffer\\(\\d+\\)\\]\\]");
    private static final Pattern COLOR=Pattern.compile("float4 (\\w+) \\[\\[color\\(0\\)\\]\\]");
    private static final Pattern FRONT=Pattern.compile("bool (\\w+) \\[\\[front_facing\\]\\]");
    private static final String MEMBERS="    float3 siliconshadersWorld [[user(locn12)]];\n    float3 siliconshadersNormal [[user(locn13)]];\n    float4 siliconshadersTint [[user(locn14)]];\n    float2 siliconshadersLight [[user(locn15)]];\n";
    private static final String FLIP="out.gl_Position.y = -(out.gl_Position.y);";
    private static final class Library { static final String TEXT=library(); }
    private SunEntityMsl() {}

    static boolean candidate(String name){return name.startsWith("minecraft:pipeline/")&&NAMES.contains(name.substring("minecraft:pipeline/".length()));}
    private static String body(String source,String name){int a=source.indexOf("struct "+name+"\n{");int b=a<0?-1:source.indexOf("};",a);return b<0?"":source.substring(a,b);}
    private static String resource(String name){try(var in=SunEntityMsl.class.getResourceAsStream("/siliconshaders/own/"+name)){if(in==null)throw new IllegalStateException(name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}
    private static String between(String text,String a,String b){int start=text.indexOf(a),end=start<0?-1:text.indexOf(b,start);if(end<0)throw new IllegalStateException("Missing shared lighting section: "+a);return text.substring(start,end);}
    // Entity shaders are built without function constants: their copy decides the lighting effects at run time.
    private static String unspecialized(String text){
        int a=text.indexOf("// [lit specialization]"),b=text.indexOf("// [/lit specialization]");
        if(a<0||b<a)throw new IllegalStateException("Missing lit specialization block");
        return text.substring(0,a)+"constant bool SUN_SKY_FX=true,SUN_WET=true,SUN_COLORED=true;\n"+text.substring(b+"// [/lit specialization]".length());
    }
    private static String library(){
        String terrain=resource("terrain.metal"),sun=resource("sun.metal");
        // Use the terrain's actual definitions and lighting/sky functions, not a second approximate model.
        return between(terrain,"struct SunFrame {","constant bool WATER_SET")
            +between(terrain,"struct SunBlendFrame {","constant bool CUTOUT [[function_constant(0)]]")
            +unspecialized(between(sun,"struct SunLighting {","// Independent Metal material shading."))
            +between(sun,"// Native sky (SunTuning group SKY)","// A sky pixel (no terrain")+resource("entity-lighting.metal")+"\n";
    }

    static Pair patch(String name,String vertex,String fragment,int uniforms){
        if(!candidate(name)||uniforms>24)return null;
        for(int location=12;location<=15;location++)if(vertex.contains("user(locn"+location+")")||fragment.contains("user(locn"+location+")"))return skipped(name);
        for(int slot:new int[]{24,25,28})if(vertex.contains("[[buffer("+slot+")]]")||fragment.contains("[[buffer("+slot+")]]"))return skipped(name);
        for(int slot=24;slot<=35;slot++)if(fragment.contains("[[texture("+slot+")]]"))return skipped(name); // shadow and glass maps
        String vi=body(vertex,"main0_in"),vo=body(vertex,"main0_out"),fi=body(fragment,"main0_in"),fo=body(fragment,"main0_out");
        Matcher project=PROJECT.matcher(vertex),dt=DT.matcher(fragment),fog=FOG.matcher(fragment),color=COLOR.matcher(fo);
        int entry=fragment.indexOf("fragment main0_out main0(");
        if(!vi.contains(" Color [[attribute(")||!vi.contains(" UV2 [[attribute(")||!vo.contains("float4 gl_Position [[position]];")
            ||!project.find()||!vertex.contains(FLIP)||entry<0||!dt.find(entry)||!fog.find(entry)||!color.find()
            ||!fi.contains(" texCoord0 [[user(")||!fragment.contains("texture2d<float> Sampler0 [[texture(")
            ||!vertex.contains("texture2d<float> Sampler2 [[texture(")||vertex.contains("user(locn12)")||fragment.contains("[[position]]")
            ||fragment.contains("Oit")||fragment.contains("oit"))return skipped(name);
        int open=fragment.indexOf('(',entry),depth=0,close=-1;
        for(int i=open;i<fragment.length();i++){char c=fragment.charAt(i);if(c=='(')depth++;else if(c==')'&&--depth==0){close=i;break;}}
        int ret=fragment.lastIndexOf("return out;");if(close<0||ret<close)return skipped(name);
        Matcher front=FRONT.matcher(fragment.substring(entry,close));String facing=front.find()?front.group(1):"siliconshadersFront";
        String params=", constant SunLighting& siliconshadersLighting [[buffer(24)]], constant SunEntityFrame& siliconshadersSun [[buffer(25)]]"
            +", device const uint* siliconshadersColored [[buffer(28)]], array<depth2d<float>, 4> siliconshadersShadows [[texture(24)]], array<depth2d<float>, 4> siliconshadersGlassDepth [[texture(28)]], array<texture2d<float>, 4> siliconshadersGlassColor [[texture(32)]]";
        if(facing.equals("siliconshadersFront"))params+=", bool siliconshadersFront [[front_facing]]";
        String overlay=fi.contains(" overlayColor [[user(")?"in.overlayColor":"float4(0,0,0,1)";
        String glint="float3(0)";
        if(fragment.contains("GlintSampler")){
            Matcher globals=Pattern.compile("constant Globals& (\\w+) \\[\\[buffer\\(\\d+\\)\\]\\]").matcher(fragment);
            if(!fi.contains(" texCoordGlint [[user(")||!globals.find(entry))return skipped(name);
            glint="pow("+globals.group(1)+".GlintAlpha * GlintSampler.sample(GlintSamplerSmplr, in.texCoordGlint).rgb, 2.0)";
        }
        String statement="if (siliconshadersLighting.enabled) out."+color.group(1)+".rgb = sunEntityColor(Sampler0.sample(Sampler0Smplr, in.texCoord0).rgb * in.siliconshadersTint.rgb * "+dt.group(1)+".ColorModulator.rgb, "+overlay+", in.siliconshadersWorld, in.siliconshadersNormal, in.siliconshadersLight, "+facing+", "+glint+", "+fog.group(1)+", siliconshadersSun, siliconshadersLighting, siliconshadersShadows, siliconshadersGlassDepth, siliconshadersGlassColor, siliconshadersColored);\n    ";
        String f=fragment.substring(0,close)+params+fragment.substring(close,ret)+statement+fragment.substring(ret);
        int fiEnd=f.indexOf("};",f.indexOf("struct main0_in\n{"));f=f.substring(0,fiEnd)+MEMBERS+f.substring(fiEnd);
        int libAt=f.indexOf("fragment main0_out main0(");f=f.substring(0,libAt)+Library.TEXT+f.substring(libAt);
        String normal=vi.contains(" Normal [[attribute(")?"in.Normal":"float3(0)";
        String v=vertex.replace("    float4 gl_Position [[position]];",MEMBERS+"    float4 gl_Position [[position]];");
        int ve=v.indexOf("vertex main0_out main0("),vc=v.indexOf("\n{",ve);
        if(ve<0||vc<0)return skipped(name);
        int vp=v.lastIndexOf(')',vc);
        v=v.substring(0,vp)+", constant SunEntityModel& siliconshadersModel [[buffer(24)]]"+v.substring(vp);
        v=v.substring(0,ve)+"struct SunEntityModel { float4x4 model; float3x3 normal; };\n"+v.substring(ve);
        v=v.replace(FLIP,"out.siliconshadersWorld = (siliconshadersModel.model * float4("+project.group(1)+", 1.0)).xyz;\n    out.siliconshadersNormal = siliconshadersModel.normal * "+normal+";\n    out.siliconshadersTint = in.Color;\n    out.siliconshadersLight = float2(in.UV2) / 240.0;\n    "+FLIP);
        return new Pair(v,f);
    }
    private static Pair skipped(String name){if(LOGGED.add(name))siliconshaders.metal.Log.info("[SiliconShaders sun] World lighting keeps unrecognized shader: "+name);return null;}
}
