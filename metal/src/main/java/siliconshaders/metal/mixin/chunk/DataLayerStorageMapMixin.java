package siliconshaders.metal.mixin.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import siliconshaders.metal.chunk.ChunkOpt;
import siliconshaders.metal.chunk.LayeredLightMap;
import siliconshaders.metal.chunk.LightSnapshots;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** C3 (-Dsiliconshaders.chunk.light=true|verify): record the updating map's mutated keys; build snapshots on copy (see LightSnapshots). */
@Mixin(DataLayerStorageMap.class)
abstract class DataLayerStorageMapMixin implements LightSnapshots {
	@Shadow @Final protected Long2ObjectOpenHashMap<DataLayer> map;

	/** Updating map: keys mutated since the last snapshot. */
	@Unique private final LongOpenHashSet siliconshaders$changed = new LongOpenHashSet();
	/** Updating map: the current lineage's base and cumulative delta, shared with (and never modified after) its snapshots. */
	@Unique private Long2ObjectOpenHashMap<DataLayer> siliconshaders$base, siliconshaders$delta;

	@Inject(method = "setLayer", at = @At("TAIL"))
	private void siliconshaders$set(long sectionNode, DataLayer layer, CallbackInfo ci) {
		this.siliconshaders$changed.add(sectionNode);
	}

	@Inject(method = "removeLayer", at = @At("TAIL"))
	private void siliconshaders$remove(long sectionNode, CallbackInfoReturnable<DataLayer> cir) {
		this.siliconshaders$changed.add(sectionNode);
	}

	@Inject(method = "copyDataLayer", at = @At("TAIL"))
	private void siliconshaders$copy(long sectionNode, CallbackInfoReturnable<DataLayer> cir) {
		this.siliconshaders$changed.add(sectionNode);
	}

	@Override
	public void siliconshaders$snapshotInto(Object target) {
		if (this.map instanceof LayeredLightMap) throw new IllegalStateException("siliconshaders light snapshot copied");
		LayeredLightMap out = (LayeredLightMap) ((DataLayerStorageMapMixin) target).map;
		if (this.siliconshaders$base == null || this.siliconshaders$delta.size() + this.siliconshaders$changed.size() > Math.max(512, this.siliconshaders$base.size() / 8)) {
			this.siliconshaders$base = this.map.clone();
			this.siliconshaders$delta = new Long2ObjectOpenHashMap<>(0);
			if (ChunkOpt.STATS) ChunkOpt.count("light.rebase");
		} else {
			Long2ObjectOpenHashMap<DataLayer> delta = this.siliconshaders$delta.clone();
			for (LongIterator it = this.siliconshaders$changed.iterator(); it.hasNext(); ) {
				long k = it.nextLong();
				DataLayer v = this.map.get(k);
				delta.put(k, v == null ? REMOVED : v);
			}
			this.siliconshaders$delta = delta;
			if (ChunkOpt.STATS) ChunkOpt.count("light.delta");
		}
		this.siliconshaders$changed.clear();
		out.base = this.siliconshaders$base;
		out.delta = this.siliconshaders$delta;
		if (ChunkOpt.LIGHT_VERIFY) this.siliconshaders$verify(out);
	}

	@Unique
	private void siliconshaders$verify(LayeredLightMap snapshot) {
		Long2ObjectOpenHashMap<DataLayer> vanilla = this.map.clone();
		boolean same = true;
		for (Long2ObjectMap.Entry<DataLayer> e : vanilla.long2ObjectEntrySet()) {
			if (snapshot.get(e.getLongKey()) != e.getValue()) same = false;
		}
		for (LongIterator it = snapshot.delta.keySet().iterator(); it.hasNext(); ) {
			long k = it.nextLong();
			if (snapshot.get(k) != vanilla.get(k)) same = false;
		}
		for (LongIterator it = snapshot.base.keySet().iterator(); it.hasNext(); ) {
			long k = it.nextLong();
			if (!snapshot.delta.containsKey(k) && !vanilla.containsKey(k)) same = false;
		}
		ChunkOpt.count(same ? "light.verify.same" : "light.verify.MISMATCH");
	}
}
