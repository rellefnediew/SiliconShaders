package siliconshaders.metal.mixin;

import siliconshaders.metal.AtlasDouble;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** the removed flag metal.atlasDouble: the blocks atlas double-buffered (see AtlasDouble). Does nothing without the flag. */
@Mixin(TextureAtlas.class)
public class AtlasDoubleMixin {
	@Inject(method = "upload", at = @At("RETURN"))
	private void siliconshaders$adUploaded(CallbackInfo ci) {
		
	}

	@Inject(method = "releaseTextures", at = @At("HEAD"))
	private void siliconshaders$adRelease(CallbackInfo ci) {
		
	}

	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"))
	private void siliconshaders$adBefore(CallbackInfo ci) {
		
	}

	@Inject(method = "uploadAnimationFrames", at = @At("RETURN"))
	private void siliconshaders$adAfter(CallbackInfo ci) {
		
	}
}
