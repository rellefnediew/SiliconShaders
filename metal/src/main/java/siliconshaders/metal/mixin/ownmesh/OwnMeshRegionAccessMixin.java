package siliconshaders.metal.mixin.ownmesh;

import siliconshaders.metal.own.OwnRegionAccess;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** -Dsiliconshaders.own.mesh.prefill: the region's section copies, for OwnRegion's unpack. */
@Mixin(RenderSectionRegion.class)
abstract class OwnMeshRegionAccessMixin implements OwnRegionAccess {
	@Shadow
	@Final
	private SectionCopy[] sections;

	@org.spongepowered.asm.mixin.Unique
	private siliconshaders.metal.own.@org.jspecify.annotations.Nullable OwnRegion siliconshaders$own;

	@Override
	public siliconshaders.metal.own.@org.jspecify.annotations.Nullable OwnRegion siliconshaders$own() {
		return this.siliconshaders$own;
	}

	@Override
	public void siliconshaders$own(siliconshaders.metal.own.@org.jspecify.annotations.Nullable OwnRegion own) {
		this.siliconshaders$own = own;
	}

	@Override
	public SectionCopy[] siliconshaders$sections() {
		return this.sections;
	}
}
