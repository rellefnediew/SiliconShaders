package siliconshaders.metal;

import java.nio.file.*;
import java.util.*;
import siliconshaders.metal.settings.*;

/** Upgrade contracts: preferences and shared codes survive the product rename. */
public final class BrandMigrationChecks {
    private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path game=Files.createTempDirectory("siliconshaders-upgrade-");
        try {
            // Use real production stores to construct a complete previous-version preferences library.
            GraphicsSettings initial=new GraphicsSettings(game);
            Map<String,Float> values=new LinkedHashMap<>(initial.capture());
            values.put("camera.bloomStrength",.23f);values.put("look.exposure",1.35f);values.put("shadow.size",2048f);
            initial.commit(values);
            values=initial.capture();
            GraphicsProfile profile=new GraphicsProfile(UUID.randomUUID().toString(),"M1 Pro",GraphicsProfile.Scope.ALL,GraphicsCatalog.portable(values,GraphicsProfile.Scope.ALL));
            ProfileStore profiles=new ProfileStore(game);profiles.put(profile);
            SettingsFiles.write(profiles.activationDocument(profile.id()));
            Path config=game.resolve("config");Map<Path,byte[]> originals=new LinkedHashMap<>();
            try(var paths=Files.list(config)) {
                for(Path current:paths.toList()) {
                    Path legacy=current.resolveSibling(current.getFileName().toString().replace("siliconshaders","mcopt"));
                    Files.move(current,legacy);originals.put(legacy,Files.readAllBytes(legacy));
                }
            }
            String flags="profile=indie\nmcopt.metal=false\nmcopt.gpu.auto=false\n#mcopt.lod=true\n";
            Files.writeString(config.resolve("mcopt.properties"),flags);
            BrandMigration.settings(game);
            require(new GraphicsSettings(game).capture().equals(values),"Upgrade changed existing graphics values");
            ProfileStore imported=new ProfileStore(game);
            require(imported.active().equals(profile.id())&&imported.find(profile.id()).values().equals(profile.values()),"Profile identity, values or active selection lost");
            for(var original:originals.entrySet())require(Arrays.equals(original.getValue(),Files.readAllBytes(original.getKey())),"Upgrade modified a legacy backup");
            require(Files.readString(config.resolve("mcopt.properties")).equals(flags),"Original JVM settings changed");
            require(Files.readString(config.resolve("siliconshaders.properties")).contains("siliconshaders.metal=false"),"JVM setting namespace not migrated");
            Path current=config.resolve("siliconshaders-camera.json");Files.writeString(current,"newer user settings");
            BrandMigration.settings(game);require(Files.readString(current).equals("newer user settings"),"Repeated migration overwrote current settings");
            String code=ProfileCodec.encode(profile);
            require(code.startsWith("SILICONSHADERS1:"),"Export uses the wrong brand");
            String legacyCode=code.replace("SILICONSHADERS1:","MCOPT1:");
            require(ProfileCodec.decode(legacyCode).values().equals(profile.values()),"Previous-version shared profile rejected");
            if(args.length>0) {
                GraphicsProfile actual=ProfileCodec.decode(Files.readString(Path.of(args[0])));
                require(actual.name().equals("M1 Pro")&&actual.values().size()==266,"Real saved M1 Pro profile failed to import");
            }
            System.setProperty("mcopt.brandCheck","legacy");System.setProperty("mcopt.brandOverride","old");System.setProperty("siliconshaders.brandOverride","new");
            try {
                BrandMigration.systemProperties();
                require(System.getProperty("siliconshaders.brandCheck").equals("legacy"),"Old launcher flag ignored");
                require(System.getProperty("siliconshaders.brandOverride").equals("new"),"Explicit new flag overwritten");
            } finally {
                for(String key:List.of("mcopt.brandCheck","mcopt.brandOverride","siliconshaders.brandCheck","siliconshaders.brandOverride"))System.clearProperty(key);
            }
            try(var icon=BrandMigrationChecks.class.getResourceAsStream("/assets/siliconshaders/textures/gui/icon.png")) {
                require(icon!=null,"Brand icon missing");var image=javax.imageio.ImageIO.read(icon);require(image.getWidth()==256&&image.getHeight()==256,"Icon dimensions changed");
                require(image.getColorModel().hasAlpha(),"Icon lost transparency");
            }
            System.out.println("PASS: old settings, profiles and active selection imported; originals preserved; new files/flags take precedence; legacy clipboard codes accepted; icon packaged");
        } finally {try(var paths=Files.walk(game)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
