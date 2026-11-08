package siliconshaders.metal.mixin.own;

import siliconshaders.metal.own.AnimOnePass;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dsiliconshaders.own.int.animOnePass: the tick's noted animation sprites drawn (one pass) and copied when the texture manager's tick ends. */
@Mixin(TextureManager.class)
public class OwnAnimFlushMixin {
	@Inject(method = "tick", at = @At("RETURN"))
	private void siliconshaders$animFlush(CallbackInfo ci) {
		AnimOnePass.flush();
	}
}
