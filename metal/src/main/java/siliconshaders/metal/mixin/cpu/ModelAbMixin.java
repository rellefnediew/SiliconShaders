package siliconshaders.metal.mixin.cpu;

import siliconshaders.metal.cpu.Ab;
import siliconshaders.metal.cpu.Cpu;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dsiliconshaders.cpu.model=ab: times FeatureRenderDispatcher.prepareFrame (where models are built) with the lever alternately off/on. */
@Mixin(FeatureRenderDispatcher.class)
abstract class ModelAbMixin {
	@Unique private static final Ab siliconshaders$ab = new Ab("model.prepareFrame");

	@Inject(method = "prepareFrame", at = @At("HEAD"))
	private void siliconshaders$begin(CallbackInfoReturnable<?> cir) {
		if (Cpu.MODEL_AB) Cpu.modelActive = siliconshaders$ab.begin();
	}

	@Inject(method = "prepareFrame", at = @At("RETURN"))
	private void siliconshaders$end(CallbackInfoReturnable<?> cir) {
		if (Cpu.MODEL_AB) siliconshaders$ab.end();
	}
}
