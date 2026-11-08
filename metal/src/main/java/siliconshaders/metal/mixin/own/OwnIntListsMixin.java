package siliconshaders.metal.mixin.own;

import java.util.List;
import java.util.Map;
import siliconshaders.metal.own.OwnTerrain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dsiliconshaders.own.int.lists=true: vanilla's per-frame section draw lists (LevelRenderer.extractSectionDrawGroups, every visible section x
 * every layer: its draw, then its slice in vanilla's uber buffers) are skipped when vanilla's uber buffers hold no geometry at all,
 * which is the case while our near terrain takes every layer (nothing of ours is ever uploaded there). Exact: with every uber buffer
 * empty, getRenderSectionSlice returns null for every section and layer, so vanilla's walk adds no draw, no section info and no
 * index count; the skip returns the same 0 with the same empty lists. Any vanilla-held layer (improved transparency, a layer outside
 * the compact range, the frame after a toggle) makes a buffer non-empty and the walk runs as before. Checked under vanilla's own
 * lock, as its walk reads the buffers.
 */
@Mixin(LevelRenderer.class)
abstract class OwnIntListsMixin {
	@Unique
	private static final String SILICONSHADERS$MODE = System.getProperty("siliconshaders.own.int.lists", "false");
	@Unique
	private static final boolean SILICONSHADERS$ON = "true".equals(SILICONSHADERS$MODE), SILICONSHADERS$VERIFY = "verify".equals(SILICONSHADERS$MODE);
	@Unique
	private boolean siliconshaders$wouldSkip;
	@Unique
	private long siliconshaders$checked, siliconshaders$bad, siliconshaders$skipped, siliconshaders$lastLog;

	@Inject(method = "extractSectionDrawGroups", at = @At("HEAD"), cancellable = true)
	private void siliconshaders$skipVanillaLists(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups, CallbackInfoReturnable<Integer> cir) {
		this.siliconshaders$wouldSkip = false;
		if (!SILICONSHADERS$ON && !SILICONSHADERS$VERIFY || OwnTerrain.get() == null) return;
		SectionRenderDispatcher d = ((LevelRenderer) (Object) this).sectionRenderDispatcher();
		if (d == null) return;
		d.lock();
		try {
			for (Object buffers : ((OwnIntDispatcherAccess) d).siliconshaders$uberBuffers().values()) {
				if (!((OwnIntUberAccess) ((OwnIntUberBuffersAccess) buffers).siliconshaders$vertexBuffer()).siliconshaders$allocations().isEmpty()) return;
			}
		} finally {
			d.unlock();
		}
		this.siliconshaders$wouldSkip = true;
		if (SILICONSHADERS$ON) cir.setReturnValue(0);
	}

	/** -Dsiliconshaders.own.int.lists=verify: vanilla's walk runs; whenever the skip would have applied, it must have produced nothing. */
	@Inject(method = "extractSectionDrawGroups", at = @At("RETURN"))
	private void siliconshaders$verifyVanillaLists(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups, CallbackInfoReturnable<Integer> cir) {
		if (!SILICONSHADERS$VERIFY) return;
		this.siliconshaders$checked++;
		if (this.siliconshaders$wouldSkip) {
			this.siliconshaders$skipped++;
			boolean empty = cir.getReturnValueI() == 0 && sectionInfos.isEmpty();
			for (Object l : drawGroups.values()) empty &= ((List<?>) l).isEmpty();
			if (!empty) this.siliconshaders$bad++;
		}
		long now = System.nanoTime();
		if (now - this.siliconshaders$lastLog > 5_000_000_000L) {
			siliconshaders.metal.Log.info("siliconshaders-own lists verify: " + this.siliconshaders$checked + " frames, skip would apply in " + this.siliconshaders$skipped + ", mismatches " + this.siliconshaders$bad);
			this.siliconshaders$lastLog = now;
		}
	}
}
