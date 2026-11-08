package siliconshaders.metal.mixin.pack;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The lightning sky flash vanilla's lightmap reads, for the native lighting (SunWeather). */
@Mixin(ClientLevel.class)
public interface SunSkyFlashAccess {
    @Invoker("getSkyFlashTime") int siliconshaders$skyFlashTime();
}
