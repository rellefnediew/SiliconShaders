package siliconshaders.metal.mixin.fast;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import siliconshaders.metal.fast.SmartLeaves;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Smart Leaves (SmartLeaves): a leaf face toward another leaf block is skipped with cutout leaves on, too. No allocation per face. */
@Mixin(LeavesBlock.class)
abstract class SmartLeavesMixin {
    @ModifyReturnValue(method="skipRendering",at=@At("RETURN"))
    private boolean siliconshaders$smartLeaves(boolean skip,BlockState state,BlockState neighbor,Direction direction) {
        return skip||SmartLeaves.skip(state,neighbor);
    }
}
