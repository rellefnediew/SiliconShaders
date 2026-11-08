package siliconshaders.metal.mixin.chunk;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** D3 (ticks=true): the level's block-position LCG state used by getBlockRandomPos. */
@Mixin(Level.class)
public interface LevelRandAccessor {
	@Accessor("randValue")
	int siliconshaders$randValue();

	@Accessor("randValue")
	void siliconshaders$randValue(int value);
}
