package siliconshaders.metal.own;

import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/** -Dsiliconshaders.own.mesh.prefill: accessors mixed into RenderSectionRegion (and Copy into SectionCopy). */
public interface OwnRegionAccess {
	SectionCopy[] siliconshaders$sections();

	/** -Dsiliconshaders.own.mesh.frapiRegion: the compile cache serving this region while our compile runs on it, else null. */
	@Nullable OwnRegion siliconshaders$own();

	void siliconshaders$own(@Nullable OwnRegion own);

	interface Copy {
		@Nullable PalettedContainer<BlockState> siliconshaders$section();

		boolean siliconshaders$debug();
	}
}
