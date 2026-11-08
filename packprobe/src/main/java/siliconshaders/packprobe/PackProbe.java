package siliconshaders.packprobe;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Compiler experiment only. Does not register a mod or install a game render hook. */
public final class PackProbe {
    private PackProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: PackProbe PACK.zip OUTPUT_DIR [SETTINGS.txt]");
        Path archive = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath();
        if (output.equals(archive.getParent())) throw new IllegalArgumentException("Use a separate experiment output directory");
        Map<String, String> options = new LinkedHashMap<>();
        if (args.length == 3) {
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(args[2]))) { p.load(reader); }
            p.stringPropertyNames().stream().sorted().forEach(k -> options.put(k, p.getProperty(k)));
        }
        byte[] before = hash(archive);
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Standalone final-pass compiler probe; not a working Minecraft shader mod");
        report.put("pack", archive.getFileName().toString());
        report.put("sha256", HexFormat.of().formatHex(before));
        report.put("numericDefineOptions", options);
        report.put("note", "Only matching numeric #defines are overridden. const values such as shadowDistance and full Iris profiles are not applied.");
        try (var pack = new PackArchive(archive)) {
            Map<String, Long> stages = new LinkedHashMap<>();
            for (String ext : new String[] {"vsh", "fsh", "csh", "gsh", "tcs", "tes"})
                stages.put(ext, pack.entries().stream().filter(n -> n.endsWith("." + ext)).count());
            report.put("archiveStageCounts", stages);
            for (boolean vertex : new boolean[] {true, false}) {
                String stem = vertex ? "final.vsh" : "final.fsh";
                String path = "world0/" + stem;
                String expanded = pack.expand(path, options);
                var result = ShaderCompiler.compile(expanded, path, vertex);
                Files.writeString(output.resolve(stem + ".glsl"), result.glsl());
                Files.write(output.resolve(stem + ".spv"), result.spirv());
                Files.writeString(output.resolve(stem + ".metal"), result.metal());
                report.put(vertex ? "vertex" : "fragment", Map.of("file", stem + ".metal", "entryPoint", result.entryPoint(),
                    "buffers", result.buffers(), "textures", result.textures(), "sourceMap", pack.sources()));
                System.out.println(path + ": GLSL -> SPIR-V -> Metal OK (" + result.spirv().length + " SPIR-V bytes)");
            }
        }
        if (!MessageDigest.isEqual(before, hash(archive))) throw new IllegalStateException("Pack archive changed during probe");
        report.put("archiveUnchanged", true);
        Files.writeString(output.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("Original ZIP unchanged. Results: " + output);
    }

    private static byte[] hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }
}
