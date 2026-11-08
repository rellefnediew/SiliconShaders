package siliconshaders.metal.settings;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.network.chat.Component;
import siliconshaders.metal.own.RenderScale;
import siliconshaders.metal.sun.SunTuning;

/**
 * Once per game start, on the main menu: the Minecraft and render-resolution settings the Standard profile is tuned for.
 * A 1920-pixel-wide picture (1920x1080 monitors, 1920x1200 laptops); Apply selects a supported Full HD mode even on a
 * larger display. Without one, the MetalFX render resolution that renders about 1920 pixels wide is recommended, but
 * never switched on by the mod: the player sets it in the graphics menu.
 * Exclusive fullscreen; anisotropic 8x texture filtering; 4 mipmap levels; improved transparency off. Whatever differs is
 * shown with Apply (all but MetalFX), Later and Don't show again. Clouds are different: the Standard profile has none, so
 * every start switches them off, notice or not (config/siliconshaders-startup.json). Waits for the menu, whichever launcher
 * owns it, and stays out of the way of a benchmark start.
 */
public final class StartupAdvice {
    static final int WIDTH=1920,MIPMAPS=4,ANISOTROPY_BIT=3; // 2^3 = 8x
    private static final long SETTLE_NANOS=1_000_000_000L;
    /** What differs: the line shown, and how to change it (null: a recommendation the player follows in the menu). */
    record Item(Component text,Runnable apply,boolean textures) {}
    private static boolean done;
    private static boolean cloudsChecked,cloudsChanged;
    private static long readySince=Long.MIN_VALUE;
    private StartupAdvice() {}

    private static Path file() { return FabricLoader.getInstance().getGameDir().resolve("config/siliconshaders-startup.json"); }
    static boolean hidden() {
        try { return Files.isRegularFile(file())&&Files.readString(file(),StandardCharsets.UTF_8).contains("\"hideRecommendedSettings\": true"); }
        catch(Exception unreadable) { return false; }
    }
    static void hide() {
        try { SettingsFiles.write(java.util.Map.of(file(),"{\n  \"hideRecommendedSettings\": true\n}\n")); }
        catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders] Could not save the recommended-settings choice: "+failure.getMessage()); }
    }

    /** Called every client tick. */
    public static void frame(Minecraft mc) {
        if(!cloudsChecked){cloudsOff(mc);cloudsChecked=true;}
        if(done)return;
        if(mc.level!=null){done=true;return;} // straight into a world: not this start
        if(siliconshaders.metal.bench.ShaderBench.busy()||mc.getSingleplayerServer()!=null||mc.getConnection()!=null||mc.gui.overlay()!=null||mc.gui.screen()==null){readySince=Long.MIN_VALUE;return;}
        long now=System.nanoTime();
        if(readySince==Long.MIN_VALUE){readySince=now;return;}
        if(now-readySince<SETTLE_NANOS)return;
        done=true;
        if(hidden())return;
        try {
            List<Item> items=check(mc);
            if(!items.isEmpty())mc.gui.setScreen(new StartupAdviceScreen(mc.gui.screen(),items));
        } catch(RuntimeException failure) { siliconshaders.metal.Log.warn("[SiliconShaders] Recommended-settings check skipped: "+failure.getMessage()); }
    }

    /** The MetalFX level (RenderScale factors 100/85/67/50 %) that renders closest to, but not below, about 1920 pixels wide. */
    static int recommendedScale(int width) {
        int best=0;
        for(int level=1;level<4;level++)if(width*RenderScale.factor(level)>=WIDTH*.97f)best=level;
        return best;
    }

    static List<Item> check(Minecraft mc) {
        List<Item> items=new ArrayList<>();var options=mc.options;var window=mc.getWindow();
        int width=window.getWidth(),height=window.getHeight();
        boolean exclusive=options.fullscreen().get()&&options.exclusiveFullscreen().get();
        Optional<VideoMode> mode=fullHdMode(width,height,exclusive,window.findBestMonitor());
        if(mode.isPresent())items.add(new Item(Component.translatable("siliconshaders.advice.resolution",width,height,mode.get().getWidth(),mode.get().getHeight()),()->{
            // Select the mode before entering exclusive fullscreen. Apply the request now, and save() below persists it.
            window.setPreferredFullscreenVideoMode(mode);
            options.exclusiveFullscreen().set(true);options.fullscreen().set(true);
            window.changeFullscreenVideoMode();window.updateFullscreenIfChanged();
        },false));
        int targetWidth=mode.map(VideoMode::getWidth).orElse(width),targetHeight=mode.map(VideoMode::getHeight).orElse(height);
        int scale=Math.round(SunTuning.get().value(SunTuning.RENDER_SCALE));
        if(targetWidth>WIDTH) {
            int wanted=recommendedScale(targetWidth);
            if(scale!=wanted)items.add(new Item(Component.translatable("siliconshaders.advice.metalfx",width,height,percent(wanted),Math.round(width*RenderScale.factor(wanted))),null,false));
        } else {
            if(scale!=0)items.add(new Item(Component.translatable("siliconshaders.advice.metalfxOff",targetWidth,targetHeight),null,false));
        }
        if(cloudsChanged)items.add(new Item(Component.translatable("siliconshaders.advice.clouds"),null,false));
        if(mode.isEmpty()&&(!options.fullscreen().get()||!options.exclusiveFullscreen().get())) // the Full HD line already sets it
            items.add(new Item(Component.translatable("siliconshaders.advice.exclusive"),()->{options.fullscreen().set(true);options.exclusiveFullscreen().set(true);},false));
        if(options.improvedTransparency().get())
            items.add(new Item(Component.translatable("siliconshaders.advice.transparency"),()->options.improvedTransparency().set(false),false));
        if(options.textureFiltering().get()!=TextureFilteringMethod.ANISOTROPIC||options.maxAnisotropyBit().get()!=ANISOTROPY_BIT)
            items.add(new Item(Component.translatable("siliconshaders.advice.filtering"),()->{options.textureFiltering().set(TextureFilteringMethod.ANISOTROPIC);options.maxAnisotropyBit().set(ANISOTROPY_BIT);},true));
        if(options.mipmapLevels().get()!=MIPMAPS)
            items.add(new Item(Component.translatable("siliconshaders.advice.mipmaps",options.mipmapLevels().get(),MIPMAPS),()->options.mipmapLevels().set(MIPMAPS),true));
        return items;
    }

    /** Clouds are always off with SiliconShaders: switched off on every start, whatever the notice choice. */
    static void cloudsOff(Minecraft mc) {
        if(mc.options.cloudStatus().get()==CloudStatus.OFF)return;
        mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.save();
        cloudsChanged=true;
        siliconshaders.metal.Log.info("[SiliconShaders] Clouds switched off (always off with SiliconShaders)");
    }

    static boolean applicable(List<Item> items) { for(Item item:items)if(item.apply()!=null)return true;return false; }

    private static String percent(int level) { return Math.round(RenderScale.factor(level)*100)+" %"; }

    /** Keep either accepted Full HD size. Otherwise select a supported mode closest to the display's aspect ratio. */
    static Optional<VideoMode> fullHdMode(int width,int height,boolean exclusive,Monitor monitor) {
        if(exclusive&&width==WIDTH&&(height==1080||height==1200)||monitor==null)return Optional.empty();
        double aspect=(double)monitor.currentMode().getWidth()/monitor.currentMode().getHeight();
        VideoMode best=null;
        for(int i=0;i<monitor.modeCount();i++) {
            VideoMode mode=monitor.mode(i);
            if(mode.getWidth()!=WIDTH||(mode.getHeight()!=1200&&mode.getHeight()!=1080))continue;
            double distance=Math.abs((double)mode.getWidth()/mode.getHeight()-aspect);
            double bestDistance=best==null?Double.POSITIVE_INFINITY:Math.abs((double)best.getWidth()/best.getHeight()-aspect);
            if(distance<bestDistance||distance==bestDistance&&mode.getRefreshRate()>best.getRefreshRate())best=mode;
        }
        return Optional.ofNullable(best);
    }

    /** Applies every item; texture changes reload the atlases once, as the video settings screen does. */
    static void apply(Minecraft mc,List<Item> items) {
        boolean textures=false;
        for(Item item:items)if(item.apply()!=null){item.apply().run();textures|=item.textures();}
        if(textures){mc.updateMaxMipLevel(mc.options.mipmapLevels().get());mc.delayTextureReload();}
        mc.options.save();
    }
}
