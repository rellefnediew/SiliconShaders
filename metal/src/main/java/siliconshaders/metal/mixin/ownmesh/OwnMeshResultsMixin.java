package siliconshaders.metal.mixin.ownmesh;

import siliconshaders.metal.own.OwnQuadHolder;
import siliconshaders.metal.own.OwnQuads;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** -Dsiliconshaders.own.mesh=true: a compile's own quads, from our mesher to the compiled mesh. */
@Mixin(SectionCompiler.Results.class)
abstract class OwnMeshResultsMixin implements OwnQuadHolder {
	@Unique
	private @Nullable OwnQuads siliconshaders$quads;

	@Override
	public @Nullable OwnQuads siliconshaders$quads() {
		return this.siliconshaders$quads;
	}

	@Override
	public void siliconshaders$setQuads(@Nullable OwnQuads quads) {
		this.siliconshaders$quads = quads;
	}
}
