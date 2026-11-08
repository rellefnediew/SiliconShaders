package siliconshaders.metal.mixin.own;

import java.nio.ByteBuffer;
import siliconshaders.metal.own.OwnTerrain;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A compiled section's solid and cutout layers go to our arena on the worker that meshed them (vanilla's uber buffers and their
 * staging copy are skipped for those layers), and the section's mesh changes reach our section table.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class OwnRenderSectionMixin {
	@Shadow
	@Final
	public int index;
	@Shadow
	private long uploadedTime;
	@Shadow
	@Final
	private BlockPos.MutableBlockPos renderOrigin;
	@Shadow
	@Final
	SectionRenderDispatcher this$0;

	@Shadow
	public abstract SectionMesh getSectionMesh();

	@Shadow
	private void checkSectionMesh(CompiledSectionMesh compiledSectionMesh) {
	}

	@Shadow
	private void releaseSectionMesh(SectionMesh oldMesh) {
	}

	@Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"), cancellable = true)
	private void siliconshaders$ownStore(ChunkSectionLayer layer, CompiledSectionMesh key, @Nullable ByteBuffer vertexBuffer, @Nullable ByteBuffer indexBuffer,
		CallbackInfoReturnable<Boolean> cir) {
		OwnTerrain own = OwnTerrain.get();
		if (own == null || !OwnTerrain.takes(layer) || key.getSectionDraw(layer) == null) return;
        siliconshaders.metal.own.OwnQuads glassQuads=key instanceof siliconshaders.metal.own.OwnQuadHolder holder?holder.siliconshaders$quads():null;
        if(vertexBuffer!=null&&glassQuads!=null)own.storeGlass(key,glassQuads.glass);
		if (layer == ChunkSectionLayer.TRANSLUCENT) {
			if (indexBuffer == null) return;  // (vanilla always sorts translucent quads)
			if (vertexBuffer == null) {
				// a resort: only the order changes; vanilla marks nothing for it either
				own.resorted(key, indexBuffer);
				cir.setReturnValue(true);
				return;
			}
			own.storeTranslucent(key, vertexBuffer, indexBuffer,glassQuads!=null&&siliconshaders.metal.own.SurfaceMaterials.ON);
		} else {
			if (vertexBuffer == null) return;
			// -Dsiliconshaders.own.mesh: our mesher's quads (the MeshData is a placeholder)
			siliconshaders.metal.own.OwnQuads quads = key instanceof siliconshaders.metal.own.OwnQuadHolder h ? h.siliconshaders$quads() : null;
			if (quads != null) {
				own.storeQuads(key, layer, quads.layers[layer == ChunkSectionLayer.SOLID ? 0 : 1]);
				own.storeTieGroups(key, quads.groups());  // (the removed flag own.mesh.tieGroups; the same array for both layers' calls)
			} else {
				own.store(key, layer, vertexBuffer);
			}
		}
		if (siliconshaders.metal.own.OwnLife.ON) siliconshaders.metal.own.OwnLife.delay();
		this.this$0.lock();
		try {
			// OwnLife (-Dsiliconshaders.own.int.lifeFix): a compile cancelled by a reset or a newer compile doesn't publish; its mesh is released
			java.util.concurrent.atomic.AtomicBoolean cancelled = null;
			SectionMesh prev = null;
			if (siliconshaders.metal.own.OwnLife.ON) {
				Object task = siliconshaders.metal.own.OwnLife.task();
				cancelled = task instanceof OwnIntSectionTaskAccess t ? t.siliconshaders$cancelled() : null;
				if (siliconshaders.metal.own.OwnLife.preStore((SectionRenderDispatcher.RenderSection) (Object) this, key, layer, cancelled)) {
					this.releaseSectionMesh(key);
					cir.setReturnValue(true);
					return;
				}
				prev = this.getSectionMesh();
			}
			key.setVertexBufferUploaded(layer);
			key.setIndexBufferUploaded(layer);
			this.checkSectionMesh(key);
			if (siliconshaders.metal.own.OwnLife.CHECK) siliconshaders.metal.own.OwnLife.postStore((SectionRenderDispatcher.RenderSection) (Object) this, key, prev, cancelled);
		} finally {
			this.this$0.unlock();
		}
		cir.setReturnValue(true);
	}

	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void siliconshaders$ownPublished(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.published(this.index, sectionMesh, this.renderOrigin.getX(), this.renderOrigin.getY(), this.renderOrigin.getZ(), this.uploadedTime);
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void siliconshaders$ownCleared(CallbackInfo ci) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.cleared(this.index);
	}

	@Inject(method = "releaseSectionMesh", at = @At("HEAD"))
	private void siliconshaders$ownReleased(SectionMesh oldMesh, CallbackInfo ci) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.released(oldMesh);
	}
}
