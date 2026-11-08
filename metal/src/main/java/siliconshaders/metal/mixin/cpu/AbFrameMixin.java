package siliconshaders.metal.mixin.cpu;

import siliconshaders.metal.cpu.Ab;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Any -Dsiliconshaders.cpu.*=ab: counts frames for Ab's per-frame arms. */
@Mixin(GameRenderer.class)
abstract class AbFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void siliconshaders$frame(CallbackInfo ci) {
		Ab.frame++;
		if (siliconshaders.metal.cpu.Cpu.AB_SHOTS) siliconshaders.metal.cpu.AbShots.frameStart();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void siliconshaders$frameEnd(CallbackInfo ci) {
		if (siliconshaders.metal.cpu.Cpu.AB_SHOTS) siliconshaders.metal.cpu.AbShots.frameEnd();
	}
}
