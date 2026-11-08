package siliconshaders.packprobe;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline default-settings translation census; does not install a pack or replace a game renderer. */
public final class SceneProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Usage: SceneProbe PACK.zip OUTPUT_DIR");
        Path input=Path.of(args[0]).toRealPath(),output=Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        byte[] before=hash(input); var results=new ArrayList<Map<String,Object>>();
        Map<String,String> uniformDefaults=Map.of();
        int translated=0,inactive=0,failed=0,optional=0;
        try(var pack=new PackArchive(input)) {
            if(pack.entries().contains("shaders/lib/uniforms.glsl")) uniformDefaults=SceneAdapter.uniformDefaults(pack.read("lib/uniforms.glsl"));
            // Evaluate the pack's own program enable switches under the same source defaults and macOS macros.
            String properties=pack.read("shaders.properties").lines().map(line -> {
                String s=line.stripLeading();
                return s.startsWith("#") && !s.matches("#\\s*(?:if|ifdef|ifndef|elif|else|endif|define|undef|include)\\b.*")?"//"+line:line;
            }).collect(java.util.stream.Collectors.joining("\n"));
            String switches=ShaderCompiler.preprocess("#version 130\n"+pack.expand("lib/common.glsl",Map.of())+"\n"+properties,"default-program-settings");
            var disabled=new java.util.HashSet<String>();
            Files.writeString(output.resolve("preprocessed-program-settings.txt"),switches);
            switches=switches.lines().map(line -> line.replaceAll("\\s+","")).collect(java.util.stream.Collectors.joining("\n"));
            var enable=java.util.regex.Pattern.compile("(?m)^program\\.([^=\\s]+)\\.enabled=false$").matcher(switches);
            while(enable.find()) disabled.add(enable.group(1));
            for(String name:pack.entries()) {
                if(!name.matches("shaders/world(?:0|1|-1)/[^/]+\\.(?:vsh|fsh|csh)")) continue;
                String relative=name.substring("shaders/".length());
                Map<String,Object> row=new LinkedHashMap<>(); row.put("source",relative);
                String file=relative.substring(relative.indexOf('/')+1);
                if(file.startsWith("dh_") || file.startsWith("clrwl_")) {
                    row.put("status","optional-integration");row.put("reason",file.startsWith("dh_")?"Distant Horizons not enabled":"Colorwheel not enabled");
                    results.add(row);optional++;continue;
                }
                if(disabled.contains(relative.replaceFirst("\\.[^.]+$",""))) {
                    row.put("status","inactive");row.put("reason","Disabled by the pack's default macOS program settings");
                    results.add(row);inactive++;continue;
                }
                long start=System.nanoTime();
                try {
                    String expanded=pack.expand(relative,Map.of()); // Pack defaults: deliberately no saved .txt or profile overrides.
                    row.put("sourceMap",pack.sources());
                    var compiled=ShaderCompiler.compileScene(expanded,relative);
                    if(compiled==null) { row.put("status","inactive");inactive++; }
                    else {
                        String stem=relative.replace('/','_');
                        Files.writeString(output.resolve(stem+".glsl"),compiled.glsl());
                        Files.write(output.resolve(stem+".spv"),compiled.spirv());
                        Files.writeString(output.resolve(stem+".metal"),compiled.metal());
                        row.put("status","translated");row.put("metalFile",stem+".metal");row.put("entryPoint",compiled.entryPoint());
                        row.put("uniformBuffers",compiled.buffers());row.put("sampledTextures",compiled.textures());row.put("interfaces",compiled.interfaces());
                        translated++;
                    }
                } catch(Exception problem) { row.put("status","failed");row.put("error",String.valueOf(problem.getMessage()));failed++; }
                row.put("milliseconds",(System.nanoTime()-start)/1_000_000);
                results.add(row);
                System.out.println(row.get("status")+" "+relative+(row.containsKey("error")?" "+row.get("error").toString().split("\n")[0]:""));
            }
        }
        if(!MessageDigest.isEqual(before,hash(input))) throw new IllegalStateException("Source archive changed");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("scope","Offline translation of declared programs at pack source defaults; no in-game execution or FPS measurement");
        report.put("pack",input.getFileName().toString());report.put("sha256",HexFormat.of().formatHex(before));
        report.put("savedOptionsApplied",false);report.put("profileOverridesApplied",false);
        report.put("emulatedMinecraftVersion",260300);report.put("platform","macOS");
        report.put("vertexConvention","Legacy OpenGL clip input; Metal output uses inverted Y and depth range 0..1");
        report.put("uniformInitializerDefaults",uniformDefaults);
        report.put("translated",translated);report.put("inactive",inactive);report.put("optionalIntegration",optional);report.put("failed",failed);report.put("stages",results);
        Files.writeString(output.resolve("translation-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("TOTAL translated="+translated+" inactive="+inactive+" optional="+optional+" failed="+failed);
        if(failed>0) throw new IllegalStateException("Translation failures recorded in translation-report.json: "+failed);
    }
    private static byte[] hash(Path p) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(p)) { byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;) digest.update(buffer,0,n); }
        return digest.digest();
    }
}
