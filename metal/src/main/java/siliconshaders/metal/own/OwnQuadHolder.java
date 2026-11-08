package siliconshaders.metal.own;

import org.jspecify.annotations.Nullable;

/** -Dsiliconshaders.own.mesh: mixed into SectionCompiler.Results and CompiledSectionMesh, carries a compile's OwnQuads to the upload hook. */
public interface OwnQuadHolder {
	@Nullable OwnQuads siliconshaders$quads();

	void siliconshaders$setQuads(@Nullable OwnQuads quads);
}
