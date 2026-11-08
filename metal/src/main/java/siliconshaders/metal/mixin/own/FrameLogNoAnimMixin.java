package siliconshaders.metal.mixin.own;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only, NOT exact (the removed flag own.int.noAnim): texture animations are never drawn into the atlases (they freeze). */
@Mixin(TextureAtlas.class)
public class FrameLogNoAnimMixin {
	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void siliconshaders$noAnim(CallbackInfo ci) {
		ci.cancel();
	}
}
