package siliconshaders.metal.mixin.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import siliconshaders.metal.chunk.LightSnapshots;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** C3 (light=true|verify): the block-light map's copy() builds a base+delta snapshot instead of cloning (LightSnapshots). */
@Mixin(targets = "net.minecraft.world.level.lighting.BlockLightSectionStorage$BlockDataLayerStorageMap")
abstract class BlockLightMapMixin {
	@Redirect(method = "copy", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;clone()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
	private Long2ObjectOpenHashMap<DataLayer> siliconshaders$noClone(Long2ObjectOpenHashMap<DataLayer> map) {
		return new siliconshaders.metal.chunk.LayeredLightMap();
	}

	@Inject(method = "copy", at = @At("RETURN"))
	private void siliconshaders$snapshot(CallbackInfoReturnable<Object> cir) {
		((LightSnapshots) this).siliconshaders$snapshotInto(cir.getReturnValue());
	}
}
