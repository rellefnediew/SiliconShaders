package siliconshaders.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One-way, non-destructive import from the previous mcopt distribution. */
public final class BrandMigration {
    private static final List<String> SETTINGS=List.of(".properties","-sun.json","-sun-tuning.json","-sun-look.json","-camera.json","-graphics-profiles.json");
    private BrandMigration() {}

    /** Explicit new JVM flags win; old launch configurations remain usable. Run before any flags are read. */
    public static void systemProperties() {
        Properties properties=System.getProperties();
        for(String key:new ArrayList<>(properties.stringPropertyNames())) {
            if(key.startsWith("mcopt.")) {
                String renamed="siliconshaders."+key.substring("mcopt.".length());
                if(System.getProperty(renamed)==null)System.setProperty(renamed,properties.getProperty(key));
            }
        }
    }

    /** Keep legacy files as backups. Existing SiliconShaders settings always take precedence. */
    public static synchronized void settings(Path directory) {
        Path config=directory.resolve("config");
        for(String suffix:SETTINGS) {
            Path source=config.resolve("mcopt"+suffix),target=config.resolve("siliconshaders"+suffix);
            if(Files.exists(target)||!Files.isRegularFile(source))continue;
            Path staged=null;
            try {
                staged=Files.createTempFile(config,"siliconshaders-import-",".tmp");
                if(suffix.equals(".properties")) {
                    String text=Files.readString(source,StandardCharsets.UTF_8);
                    text=text.replaceAll("(?m)^(\\s*#?\\s*)mcopt\\.","$1siliconshaders.");
                    Files.writeString(staged,text,StandardCharsets.UTF_8);
                } else Files.copy(source,staged,StandardCopyOption.REPLACE_EXISTING);
                Files.move(staged,target); // no REPLACE_EXISTING: never overwrite a newer configuration
            } catch(FileAlreadyExistsException newer) {
                // Another game instance has already imported this file.
            } catch(IOException failure) {
                throw new IllegalStateException("SiliconShaders could not import settings from "+source+"; the original file is preserved",failure);
            } finally {
                if(staged!=null)try {Files.deleteIfExists(staged);}catch(IOException ignored) {}
            }
        }
    }
}
