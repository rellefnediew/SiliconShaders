package siliconshaders.metal.mixin.cpu;

import siliconshaders.metal.cpu.Ab;
import siliconshaders.metal.cpu.EntityBox;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** the removed flag cpu.entityBox times the frame's entity pass with the box reuse alternately off and on (see Ab). */
@Mixin(LevelExtractor.class)
abstract class EntityBoxAbMixin {
	@Unique private static final Ab siliconshaders$ab = new Ab("entityBox.extractVisibleEntities");

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void siliconshaders$begin(CallbackInfo ci) {
		
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void siliconshaders$end(CallbackInfo ci) {
		
	}
}
