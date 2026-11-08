package siliconshaders.metal.pack;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** File persistence is separate from the GPU runtime so settings can be checked without starting Minecraft. */
public record ShaderSettings(boolean enabled, String pack) {
    private static final Gson GSON = new Gson();

    public static Path packPath(Path gameDir, String name) throws IOException {
        if (name == null || name.isBlank() || !name.endsWith(".zip") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("Invalid shaderpack filename");
        return gameDir.resolve("shaderpacks").resolve(name);
    }

    public static ShaderSettings load(Path gameDir) throws IOException {
        Path file = gameDir.resolve("config/siliconshaders-shaders.json");
        if (!Files.isRegularFile(file)) return new ShaderSettings(false, "");
        if (Files.size(file)>65536) throw new IOException("Shader configuration exceeds limit");
        try {
            var value=GSON.fromJson(Files.readString(file),ShaderSettings.class);
            if (value==null || value.pack==null) throw new IOException("Invalid shader configuration");
            if (!value.pack.isEmpty() && !value.pack.equals(PackProgram.DIAGNOSTIC)) packPath(gameDir,value.pack);
            return new ShaderSettings(value.enabled && !value.pack.isEmpty(),value.pack);
        } catch (RuntimeException invalid) { throw new IOException("Cannot read shader configuration",invalid); }
    }

    public void save(Path gameDir) throws IOException {
        Path file=gameDir.resolve("config/siliconshaders-shaders.json"); Files.createDirectories(file.getParent());
        Path tmp=Files.createTempFile(file.getParent(),"siliconshaders-shaders-",".tmp");
        try { Files.writeString(tmp,GSON.toJson(this)+"\n"); Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(tmp); }
    }
}
