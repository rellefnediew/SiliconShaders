package siliconshaders.metal.sun;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/**
 * -Dsiliconshaders.sun.weatherShots=true: a picture check of SunWeather in a single-player world (use a test world: its weather and time
 * are changed). From the world's first ticks: noon, then clear, rain and thunder, then a clear and a rainy night, each grabbed once vanilla's
 * rain or thunder level has fully ramped (screenshots/weather-*.png); the world ends clear at noon.
 */
public final class SunWeatherShots {
    public static final boolean ON=Boolean.getBoolean("siliconshaders.sun.weatherShots");
    private static int ticks=-1;
    private SunWeatherShots() {}

    /** Each client tick (SunLighting.tick). */
    public static void tick(Minecraft mc) {
        MinecraftServer server=mc.getSingleplayerServer();
        if(!ON||mc.level==null||mc.player==null||server==null||ticks>840)return;
        switch(++ticks) {
            case 1 -> command(server,"time set 6000","weather clear","tp @a 16.5 146 -31.5 -40 22");
            case 240 -> { grab(mc,"weather-1-clear.png");command(server,"weather rain"); }
            case 380 -> { grab(mc,"weather-2-rain.png");lightBefore(true); }
            // The light as it was before SunWeather (none from the sun in full rain), for comparison; never saved.
            case 384 -> { grab(mc,"weather-2-rain-before.png");lightBefore(false);command(server,"weather thunder"); }
            case 520 -> { grab(mc,"weather-3-thunder.png");command(server,"time set 18000","weather clear"); }
            case 660 -> { grab(mc,"weather-4-night-clear.png");command(server,"weather rain"); }
            case 800 -> { grab(mc,"weather-5-night-rain.png");command(server,"time set 6000","weather clear"); }
            case 840 -> siliconshaders.metal.Log.info("[SiliconShaders sun] weather shots done");
            default -> { }
        }
    }
    private static float[] saved;
    private static void lightBefore(boolean on) {
        SunTuning tuning=SunTuning.get();
        if(on){saved=tuning.values();float[] v=saved.clone();v[SunWeather.SUN]=0;v[SunWeather.FOG]=0;tuning.benchmark(v);}
        else if(saved!=null){tuning.benchmark(saved);saved=null;}
    }
    private static void command(MinecraftServer server,String... commands) {
        server.execute(()->{ for(String c:commands)server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),c); });
    }
    private static void grab(Minecraft mc,String name) {
        Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,message->{});
        siliconshaders.metal.Log.info("[SiliconShaders sun] weather shot "+name);
    }
}
