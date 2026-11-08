package siliconshaders.metal.mixin.ownmesh;

import siliconshaders.metal.own.OwnQuadHolder;
import siliconshaders.metal.own.OwnQuads;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dsiliconshaders.own.mesh=true: the compiled mesh holds its compile's own quads until the upload hook (OwnRenderSectionMixin) has put
 * each own layer into the arena, on the same worker, in the same task.
 */
@Mixin(CompiledSectionMesh.class)
abstract class OwnMeshCompiledMixin implements OwnQuadHolder {
	@Unique
	private @Nullable OwnQuads siliconshaders$quads;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void siliconshaders$ownQuads(TranslucencyPointOfView translucencyPointOfView, SectionCompiler.Results results, long compileTaskStartTimeNs, CallbackInfo ci) {
		this.siliconshaders$quads = ((OwnQuadHolder) (Object) results).siliconshaders$quads();
	}

	@Override
	public @Nullable OwnQuads siliconshaders$quads() {
		return this.siliconshaders$quads;
	}

	@Override
	public void siliconshaders$setQuads(@Nullable OwnQuads quads) {
		this.siliconshaders$quads = quads;
	}
}
