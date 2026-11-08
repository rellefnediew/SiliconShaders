package siliconshaders.metal.sun;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;

/** Keep vanilla's tint/light coordinates, but let the native shader supply directional shading. */
public final class SunLighting {
    private static boolean lastEnabled;
    private SunLighting() {}
    public static boolean enabled() { return SunSettings.get().state().enabled(); }
    public static void unshade(QuadInstance quad) {
        if(enabled()) for(int corner=0;corner<4;corner++) quad.setColor(corner,-1);
    }
    public static void tick(Minecraft minecraft) {
        if(SunWeatherShots.ON)SunWeatherShots.tick(minecraft);
        boolean next=enabled();
        if(next!=lastEnabled) {
            lastEnabled=next;
            if(minecraft.level!=null) minecraft.levelExtractor.allChanged();
        }
    }
}
