package siliconshaders.metal.pack;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** siliconshaders needs no Fabric API resource loader. Supply our menus' missing translation keys from the mod jar. */
public final class ShaderTranslations {
    private ShaderTranslations() { }
    public static Map<String,String> withFallback(Map<String,String> existing,List<String> languages) {
        Map<String,String> fallback=new HashMap<>();
        read(fallback,"en_us");
        for(String language:languages) read(fallback,language);
        // Resource-pack translations still take precedence when a resource loader makes them available.
        fallback.putAll(existing);
        return Map.copyOf(fallback);
    }
    private static void read(Map<String,String> target,String language) {
        if(!language.matches("[a-zA-Z_]+")) return;
        try(var in=ShaderTranslations.class.getResourceAsStream("/assets/siliconshaders/lang/"+language+".json")) {
            if(in==null) return;
            var root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            // Every siliconshaders menu: shaders, camera effects, benchmark (only "siliconshaders.shaders." before, so the others showed raw keys).
            for(var entry:root.entrySet()) if(entry.getKey().startsWith("siliconshaders.")) target.put(entry.getKey(),entry.getValue().getAsString());
        } catch(Exception error) { siliconshaders.metal.Log.warn("[SiliconShaders shaders] Cannot read menu translations: "+error.getMessage()); }
    }
}
