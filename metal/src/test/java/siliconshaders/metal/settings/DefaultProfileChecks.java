package siliconshaders.metal.settings;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
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

        // MetalFX proposal for screens wider than 1920: renders about 1920 pixels wide, never clearly less.
        int[][] scales={{1920,0},{2048,0},{2560,1},{2880,2},{3024,2},{3456,2},{3840,3},{5120,3}};
        for(int[] s:scales)require(StartupAdvice.recommendedScale(s[0])==s[1],"MetalFX proposal for width "+s[0]+": "+StartupAdvice.recommendedScale(s[0]));
        resolutionChecks();
        System.out.println("PASS: Standard profile code complete and in range ("+standard.values().size()+" settings), applied and selected on a first start, never over the player's or imported MCOpt settings");
    }

    private static VideoMode mode(int width,int height,int hz){return new VideoMode(width,height,8,8,8,hz);}
    private static Monitor monitor(VideoMode current,VideoMode... supported){return new Monitor("test",0,List.of(supported),current,0,0,current.getWidth(),current.getHeight());}
    private static void resolutionChecks(){
        VideoMode hd=mode(1920,1080,60),hdFast=mode(1920,1080,120),laptop=mode(1920,1200,60);
        VideoMode retina=mode(3024,1964,120),fourK=mode(3840,2160,120);
        Monitor mac=monitor(retina,hd,laptop,retina),screen=monitor(fourK,laptop,hd,hdFast,fourK);
        require(StartupAdvice.fullHdMode(3024,1964,true,mac).orElseThrow().equals(laptop),"Retina must offer an applicable Full HD mode");
        require(StartupAdvice.fullHdMode(3840,2160,true,screen).orElseThrow().equals(hdFast),"4K must offer 16:9 Full HD with the highest refresh rate");
        require(StartupAdvice.fullHdMode(1280,800,false,mac).orElseThrow().equals(laptop),"Windowed mode must offer Full HD");
        require(StartupAdvice.fullHdMode(1920,1080,false,screen).orElseThrow().equals(hdFast),"A Full HD window still needs a Full HD fullscreen mode");
        require(StartupAdvice.fullHdMode(1920,1080,true,screen).isEmpty(),"Keep accepted 1080p fullscreen");
        require(StartupAdvice.fullHdMode(1920,1200,true,mac).isEmpty(),"Keep accepted 1200p fullscreen");
        require(StartupAdvice.fullHdMode(1920,1440,true,mac).isPresent(),"1920 pixels wide alone is not an accepted resolution");
        require(StartupAdvice.fullHdMode(3840,2160,true,monitor(fourK,fourK)).isEmpty(),"Do not request an unsupported mode");
        require(StartupAdvice.fullHdMode(1280,800,false,null).isEmpty(),"No monitor: no fullscreen change");
        System.out.println("PASS: Full HD selection for Retina, 4K, windowed, accepted sizes, refresh rates and unsupported displays");
    }
}
