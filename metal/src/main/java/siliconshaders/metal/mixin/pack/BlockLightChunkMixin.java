package siliconshaders.metal.mixin.pack;
import siliconshaders.metal.own.BlockLights;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientChunkCache.class)
abstract class BlockLightChunkMixin {
    @Inject(method="onLightUpdate",at=@At("TAIL"))
    private void siliconshaders$coloredLightUpdate(LightLayer layer,SectionPos at,CallbackInfo ci){if(layer==LightLayer.BLOCK)BlockLights.lightChanged(at.asLong());}
}
