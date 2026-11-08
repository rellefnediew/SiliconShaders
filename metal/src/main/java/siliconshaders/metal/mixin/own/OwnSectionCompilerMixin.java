package siliconshaders.metal.mixin.own;

import siliconshaders.metal.own.OwnTerrain;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stats only (-Dsiliconshaders.own.stats): the meshing time of every section compile. */
@Mixin(SectionCompiler.class)
abstract class OwnSectionCompilerMixin {
	private static final ThreadLocal<long[]> SILICONSHADERS$START = ThreadLocal.withInitial(() -> new long[1]);

	@Inject(method = "compile", at = @At("HEAD"))
	private void siliconshaders$compileStart(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting, SectionBufferBuilderPack builders,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnTerrain.STATS) SILICONSHADERS$START.get()[0] = System.nanoTime();
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void siliconshaders$compileEnd(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting, SectionBufferBuilderPack builders,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnTerrain.STATS) OwnTerrain.compiled(System.nanoTime() - SILICONSHADERS$START.get()[0]);
	}
}
