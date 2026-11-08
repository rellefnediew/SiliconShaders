package siliconshaders.metal.settings;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SiliconShaders' standard profile: the profile code in resources/siliconshaders/default-profile.txt (every setting,
 * including quality). It is the built-in "Standard" preset of the graphics menu, and a fresh installation starts with it.
 * Only a first start applies it: as soon as any SiliconShaders settings file exists (or MCOpt settings were imported),
 * the player's own settings are left alone. Plain Java and files only, so it can run before any Minecraft class loads.
 */
public final class DefaultProfile {
    public static final String ID="builtin.standard";
    private static final List<String> SETTINGS=List.of("siliconshaders-sun.json","siliconshaders-sun-tuning.json","siliconshaders-sun-look.json",
        "siliconshaders-camera.json","siliconshaders-graphics-profiles.json");
    private static volatile GraphicsProfile decoded;
    private DefaultProfile() {}

    /** The standard profile under the given display name, with only the settings this version knows, fitted to their ranges. */
    public static GraphicsProfile get(String name) {
        GraphicsProfile profile=decoded;
        if(profile==null) {
            try(InputStream in=DefaultProfile.class.getResourceAsStream("/siliconshaders/default-profile.txt")) {
                if(in==null)throw new IllegalStateException("default-profile.txt missing");
                profile=ProfileCodec.decode(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            decoded=profile;
        }
        return new GraphicsProfile(ID,name,profile.scope(),GraphicsCatalog.portable(profile.values(),profile.scope()));
    }

    /** True when this game directory has no SiliconShaders settings yet (after importing MCOpt's). */
    static boolean firstStart(Path gameDir) {
        siliconshaders.metal.BrandMigration.settings(gameDir);
        Path config=gameDir.resolve("config");
        for(String file:SETTINGS)if(Files.exists(config.resolve(file)))return false;
        return true;
    }

    /** Writes the standard profile as the initial settings, selected in the menu, on a first start. Never throws. */
    public static void applyOnFirstStart(Path gameDir) {
        try {
            if(!firstStart(gameDir))return;
            GraphicsSettings settings=new GraphicsSettings(gameDir);
            Map<String,Float> values=GraphicsCatalog.merge(settings.capture(),get("Standard"));
            settings.commit(values,new ProfileStore(gameDir).activationDocument(ID));
            siliconshaders.metal.Log.info("[SiliconShaders] First start: the Standard profile is now the initial setting");
        } catch(RuntimeException|java.io.IOException failure) {
            siliconshaders.metal.Log.warn("[SiliconShaders] Standard profile not applied on first start: "+failure.getMessage());
        }
    }
}
