package siliconshaders.metal.pack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import siliconshaders.packprobe.PackArchive;
import siliconshaders.packprobe.ShaderCompiler;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** An immutable, CPU-compiled program. GPU resources are created later on the render thread. */
public record PackProgram(String key, String json) {
    private static final Gson GSON = new Gson();
    public static final String DIAGNOSTIC = ":metal-test";
    private static final String TEST_VERTEX = """
        #version 130
        noperspective out vec2 texCoord;
        void main() { gl_Position=ftransform(); texCoord=gl_MultiTexCoord0.xy; }
        """;
    private static final String TEST_FRAGMENT = """
        #version 130
        noperspective in vec2 texCoord;
        uniform sampler2D colortex3;
        void main() { vec3 c=texture2D(colortex3,texCoord).rgb; gl_FragData[0]=vec4(c*vec3(1.12,0.94,0.80),1.0); }
        """;

    public static PackProgram compile(Path gameDir, String name, boolean reload) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        byte[] packHash, settingsBytes = new byte[0];
        Path zip = null;
        if (name.equals(DIAGNOSTIC)) packHash = (TEST_VERTEX + TEST_FRAGMENT).getBytes(StandardCharsets.UTF_8);
        else {
            zip = ShaderSettings.packPath(gameDir, name);
            if (!Files.isRegularFile(zip)) throw new IOException("Shaderpack missing: " + name);
            if (Files.size(zip) > 256L * 1024 * 1024) throw new IOException("Shaderpack exceeds 256 MiB");
            packHash = hashFile(zip);
            Path settings = zip.resolveSibling(zip.getFileName() + ".txt");
            if (Files.isRegularFile(settings)) {
                if (Files.size(settings) > 65536) throw new IOException("Shader settings exceed limit");
                settingsBytes = Files.readAllBytes(settings);
                Properties p = new Properties(); p.load(new StringReader(new String(settingsBytes, StandardCharsets.UTF_8)));
                p.stringPropertyNames().stream().sorted().forEach(k -> options.put(k, p.getProperty(k).trim()));
            }
        }
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("siliconshaders-final-pass-v2|260300|".getBytes(StandardCharsets.UTF_8));
        digest.update(org.lwjgl.Version.getVersion().getBytes(StandardCharsets.UTF_8));
        for (Class<?> type : new Class<?>[] {PackProgram.class, PackArchive.class, ShaderCompiler.class, Class.forName("siliconshaders.packprobe.GlslAdapter")}) {
            try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) { digest.update(in.readAllBytes()); }
        }
        digest.update(packHash); digest.update(settingsBytes);
        String key = HexFormat.of().formatHex(digest.digest());
        Path cache = gameDir.resolve("siliconshaders-pack-cache/final").resolve(key + ".json");
        if (!reload && Files.isRegularFile(cache) && Files.size(cache) <= 16 * 1024 * 1024) {
            try {
                String json = Files.readString(cache); validate(json);
                return new PackProgram(key, json);
            } catch (RuntimeException | IOException invalidCache) { /* Rebuild this entry from the original archive. */ }
        }
        JsonObject root = new JsonObject();
        if (zip == null) {
            root.add("vertex", stage(ShaderCompiler.compile(TEST_VERTEX, "metal-test.vsh", true)));
            root.add("fragment", stage(ShaderCompiler.compile(TEST_FRAGMENT, "metal-test.fsh", false)));
        } else try (var pack = new PackArchive(zip)) {
            if (pack.entries().contains("shaders/siliconshaders.json")) {
                // A local pretranslated experiment supplies the exact bindings with its Metal source.
                // This deliberately has the same final-image-only limits as the existing preview.
                root = GSON.fromJson(pack.read("siliconshaders.json"), JsonObject.class);
                if (root == null || !root.has("siliconshadersMetalFormat") || root.get("siliconshadersMetalFormat").getAsInt() != 1
                    || !root.has("scope") || !root.get("scope").getAsString().equals("final-image-only"))
                    throw new IOException("Unsupported pretranslated shader format or scope");
            } else {
            String prefix = pack.entries().contains("shaders/world0/final.fsh") && pack.entries().contains("shaders/world0/final.vsh") ? "world0/" : "";
            root.add("vertex", stage(ShaderCompiler.compile(pack.expand(prefix + "final.vsh", options), prefix + "final.vsh", true)));
            root.add("fragment", stage(ShaderCompiler.compile(pack.expand(prefix + "final.fsh", options), prefix + "final.fsh", false)));
            boolean needsNoise = root.getAsJsonObject("fragment").getAsJsonArray("textures").asList().stream()
                .anyMatch(t -> t.getAsJsonObject().get("name").getAsString().equals("noisetex"));
            if (needsNoise) {
                Properties properties = new Properties(); properties.load(new StringReader(pack.read("shaders.properties")));
                String noise = properties.getProperty("texture.noise", "").trim();
                if (noise.isEmpty() || !noise.endsWith(".png")) throw new IOException("This final pass needs a PNG texture.noise declaration");
                root.addProperty("noisePng", Base64.getEncoder().encodeToString(pack.readBytes(noise)));
            }
            }
            if (!MessageDigest.isEqual(packHash, hashFile(zip))) throw new IOException("Shaderpack changed while compiling; reload it");
        }
        String json = GSON.toJson(root); validate(json);
        // An unavailable cache must not prevent rendering a program that compiled correctly.
        try {
            Files.createDirectories(cache.getParent());
            Path tmp = Files.createTempFile(cache.getParent(), key, ".tmp");
            try {
                Files.writeString(tmp, json);
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException ignored) { }
        return new PackProgram(key, json);
    }

    private static JsonObject stage(ShaderCompiler.Compiled compiled) {
        JsonObject stage = new JsonObject();
        stage.addProperty("metal", compiled.metal()); stage.addProperty("entryPoint", compiled.entryPoint());
        stage.add("buffers", GSON.toJsonTree(compiled.buffers())); stage.add("textures", GSON.toJsonTree(compiled.textures()));
        return stage;
    }

    public static void validate(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        for (String name : new String[] {"vertex", "fragment"}) {
            JsonObject stage = root.getAsJsonObject(name);
            if (stage.get("metal").getAsString().length() > 4*1024*1024 || stage.get("entryPoint").getAsString().isEmpty()) throw new IllegalArgumentException("Invalid Metal program");
            for (var e : stage.getAsJsonArray("buffers")) {
                var buffer = e.getAsJsonObject(); int size = buffer.get("size").getAsInt(), slot = buffer.get("slot").getAsInt();
                if (size <= 0 || size > 4096 || slot < 0 || slot >= 30) throw new IllegalArgumentException("Unsupported final-pass uniform layout");
                for (var offset : buffer.getAsJsonObject("offsets").entrySet())
                    if (offset.getValue().getAsInt() < 0 || offset.getValue().getAsInt() + 4 > size) throw new IllegalArgumentException("Invalid uniform offset");
            }
            for (var e : stage.getAsJsonArray("textures")) {
                var t = e.getAsJsonObject(); String texture = t.get("name").getAsString(); int slot = t.get("slot").getAsInt();
                if (!texture.equals("colortex3") && !texture.equals("noisetex")) throw new IllegalArgumentException("Final pass needs unsupported texture: " + texture);
                if (slot < 0 || slot >= 31) throw new IllegalArgumentException("Unsupported texture slot");
                if (texture.equals("noisetex") && !root.has("noisePng")) throw new IllegalArgumentException("Pack noise texture missing");
            }
        }
    }

    private static byte[] hashFile(Path file) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; for (int n; (n=in.read(buffer)) != -1;) hash.update(buffer,0,n);
        }
        return hash.digest();
    }
}
