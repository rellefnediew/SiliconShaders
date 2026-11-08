package siliconshaders.metal.settings;

import java.nio.file.*;
import java.util.*;
import siliconshaders.metal.camera.CameraSettings;
import siliconshaders.metal.sun.SunSettings;

/** The shipped Standard profile: a complete, in-range code, applied on a first start only. No game or window. */
public final class DefaultProfileChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    public static void main(String[] args) throws Exception {
        GraphicsProfile standard=DefaultProfile.get("Standard");
        require(standard.id().equals(DefaultProfile.ID)&&standard.scope()==GraphicsProfile.Scope.ALL,"Standard profile identity or scope");
        GraphicsProfile raw=ProfileCodec.decode(new String(DefaultProfileChecks.class.getResourceAsStream("/siliconshaders/default-profile.txt").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        GraphicsCatalog.Fit fit=GraphicsCatalog.fit(raw);
        require(fit.skipped()==0&&fit.adjusted()==0,"Standard profile has unknown or out-of-range values: "+fit);
        Map<String,Float> merged=GraphicsCatalog.merge(GraphicsCatalog.defaults(),standard);GraphicsCatalog.validate(merged);

        // Fresh game directory: the profile becomes the settings, selected in the menu.
        Path fresh=Files.createTempDirectory("siliconshaders-default-fresh");
        DefaultProfile.applyOnFirstStart(fresh);
        Map<String,Float> applied=new GraphicsSettings(fresh).capture();int compared=0;
        for(var e:standard.values().entrySet()){Float v=applied.get(e.getKey());if(v==null)continue;compared++;
            require(Math.abs(v-e.getValue())<1e-4f,"Not applied on first start: "+e.getKey()+" "+v+" instead of "+e.getValue());}
        require(compared>=300,"Too few settings compared: "+compared);
        require(new ProfileStore(fresh).active().equals(DefaultProfile.ID),"Standard profile is not selected after first start");
        require(new CameraSettings(fresh).value(CameraSettings.FILM_GRAIN)==standard.values().get("camera.filmGrain"),"Camera store did not read the profile");

        // Existing settings: a second start, after the player changed something, keeps the player's value.
        CameraSettings camera=new CameraSettings(fresh);camera.set(CameraSettings.FILM_GRAIN,0);
        DefaultProfile.applyOnFirstStart(fresh);
        require(new CameraSettings(fresh).value(CameraSettings.FILM_GRAIN)==0,"First-start profile overwrote the player's settings");

        // Upgrading from MCOpt: imported settings win over the standard profile.
        Path legacy=Files.createTempDirectory("siliconshaders-default-legacy");Files.createDirectories(legacy.resolve("config"));
        Files.writeString(legacy.resolve("config/mcopt-sun.json"),"{\"enabled\":true,\"shadowSize\":1024,\"shadowDistance\":64}\n");
        DefaultProfile.applyOnFirstStart(legacy);
        require(new SunSettings(legacy).state().shadowSize()==1024,"Standard profile replaced imported MCOpt settings");
        require(!Files.exists(legacy.resolve("config/siliconshaders-camera.json")),"Standard profile wrote files next to imported MCOpt settings");

        System.out.println("PASS: Standard profile code complete and in range ("+standard.values().size()+" settings), applied and selected on a first start, never over the player's or imported MCOpt settings");
    }
}
