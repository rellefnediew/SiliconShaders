package siliconshaders.packprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackArchiveChecks {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempFile("packprobe-check-", ".zip");
        try {
            try (var z = new ZipOutputStream(Files.newOutputStream(temp))) {
                add(z, "shaders/world0/final.fsh", "#version 130\n/*\n#include \"/missing.glsl\"\n*/\n#include \"../lib/a.glsl\"\n");
                add(z, "shaders/lib/a.glsl", "#define QUALITY 3 // [1 2 3]\n#include \"/lib/b.glsl\"\n");
                add(z, "shaders/lib/b.glsl", "float testValue = 1.0;\n");
                add(z, "shaders/cycle.glsl", "#include \"/cycle.glsl\"\n");
                add(z, "shaders/escape.glsl", "#include \"../../outside.glsl\"\n");
            }
            byte[] original = Files.readAllBytes(temp);
            try (var pack = new PackArchive(temp)) {
                String text = pack.expand("world0/final.fsh", Map.of("QUALITY", "1"));
                require(text.contains("#define QUALITY 1"), "saved numeric option was not applied");
                require(text.contains("float testValue = 1.0;"), "relative/root includes did not resolve");
                expectFailure(() -> pack.expand("cycle.glsl", Map.of()), "cycle");
                expectFailure(() -> pack.expand("escape.glsl", Map.of()), "root escape");
                expectFailure(() -> pack.expand("world0/final.fsh", Map.of("QUALITY", "1\n#error injected")), "invalid option");
            }
            require(java.util.Arrays.equals(original, Files.readAllBytes(temp)), "source archive was modified");
            try {
                GlslAdapter.adapt("#version 450\nvoid main(){gl_FragData[1]=vec4(1);}", false);
                throw new AssertionError("Unsupported final output accepted");
            } catch (IllegalArgumentException expected) { }
            System.out.println("PASS: nested includes, commented includes, numeric settings, cycles, root escape, invalid options, archive integrity, unsupported output rejection");
            SceneChecks.run();
        } finally { Files.deleteIfExists(temp); }
    }

    private static void add(ZipOutputStream z, String path, String value) throws IOException {
        z.putNextEntry(new ZipEntry(path));
        z.write(value.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
    private static void require(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    private static void expectFailure(Action action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(label + " was not rejected");
    }
    private interface Action { void run() throws Exception; }
}
