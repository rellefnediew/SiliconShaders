package siliconshaders.metal.mixin.cpu;

import siliconshaders.metal.cpu.Ab;
import siliconshaders.metal.cpu.Cpu;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** the removed flag cpu.leash times the frame's entity pass with the leash shortcut alternately off and on (per frame). */
@Mixin(LevelExtractor.class)
abstract class LeashAbMixin {
	@Unique private static final Ab siliconshaders$ab = new Ab("leash.extractVisibleEntities");

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void siliconshaders$begin(CallbackInfo ci) {
		
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void siliconshaders$end(CallbackInfo ci) {
		
	}
}
