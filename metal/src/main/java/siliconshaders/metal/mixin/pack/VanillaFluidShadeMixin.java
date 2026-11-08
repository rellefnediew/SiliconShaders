package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.sun.SunLighting;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Fluid colours contain cardinal shading too, outside BlockModelLighter's path. */
@Mixin(FluidRenderer.class)
abstract class VanillaFluidShadeMixin {
    private static final CardinalLighting SILICONSHADERS_UNSHADED=new CardinalLighting(1,1,1,1,1,1);

    @Redirect(method="tesselate",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;"))
    private CardinalLighting siliconshaders$plainFluid(BlockAndTintGetter level) {
        return SunLighting.enabled()?SILICONSHADERS_UNSHADED:level.cardinalLighting();
    }
}
