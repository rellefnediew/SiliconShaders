package siliconshaders.metal.mixin.lod;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Ground truth (test only, -Dsiliconshaders.lod.truthRd=N): the integrated server sends chunks out to N instead of 32. */
@Mixin(ChunkMap.class)
abstract class LodTruthChunkMapMixin {
	@ModifyConstant(method = "setServerViewDistance", constant = @Constant(intValue = 32), require = 1)
	private int siliconshaders$truthView(int max) {
		return Math.max(max, Integer.getInteger("siliconshaders.lod.truthRd", max));
	}
}
