package siliconshaders.metal.mixin.own;

import siliconshaders.metal.own.AnimCopy;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** the removed flag own.int.animCopy: the atlas's animation draws through AnimCopy (a small target and copies) instead of passes over whole mips. */
@Mixin(TextureAtlas.class)
public class OwnAnimCopyMixin {
	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void siliconshaders$animCopy(CallbackInfo ci) {
		if (siliconshaders.metal.own.AnimOnePass.upload((TextureAtlas) (Object) this) || !siliconshaders.metal.own.AnimOnePass.ON && AnimCopy.upload((TextureAtlas) (Object) this)) ci.cancel();
	}

	@Inject(method = "uploadAnimationFrames", at = @At("RETURN"))
	private void siliconshaders$animVerify(CallbackInfo ci) {
		AnimCopy.afterVanilla((TextureAtlas) (Object) this);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void siliconshaders$animHash(CallbackInfo ci) {
		AnimCopy.ticked((TextureAtlas) (Object) this);
	}
}
