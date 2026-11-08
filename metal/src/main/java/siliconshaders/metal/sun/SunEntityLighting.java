package siliconshaders.metal.sun;

/** Render-thread scope: native world objects after opaque terrain, ending before hands/HUD. */
public final class SunEntityLighting {
    private static boolean world;
    private SunEntityLighting() {}
    public static void begin(){world=false;}
    public static void ready(boolean enabled){world=enabled;}
    public static void end(){world=false;}
    public static boolean active(){return world&&com.mojang.blaze3d.systems.RenderSystem.isRenderingLevel&&SunSettings.get().state().enabled();}
}
