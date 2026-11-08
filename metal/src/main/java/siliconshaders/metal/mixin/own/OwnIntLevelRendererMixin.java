package siliconshaders.metal.mixin.own;

import siliconshaders.metal.own.OwnSeam;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * -Dsiliconshaders.own.int.seam with -Dsiliconshaders.lod.fade=instant: the sections vanilla still draws itself (translucent terrain, unless
 * -Dsiliconshaders.own.translucent) appear without the fog fade-in while far terrain draws, as ours do (OwnSeam.fadeMs).
 */
@Mixin(LevelRenderer.class)
abstract class OwnIntLevelRendererMixin {
	@ModifyVariable(method = "extractSectionDrawGroups", at = @At("STORE"), ordinal = 0)
	private long siliconshaders$ownFade(long fadeDuration) {
		return OwnSeam.fadeMs(fadeDuration);
	}
}
