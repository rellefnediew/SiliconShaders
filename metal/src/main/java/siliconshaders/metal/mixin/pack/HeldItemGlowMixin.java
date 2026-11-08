package siliconshaders.metal.mixin.pack;

import com.llamalad7.mixinextras.sugar.Local;
import siliconshaders.metal.sun.SunHandLight;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Third person and other players/mobs: a held light-emitting item glows like in first person (SunHandLight.glow). */
@Mixin(ItemInHandLayer.class)
abstract class HeldItemGlowMixin {
    @ModifyVariable(method="submitArmWithItem",at=@At("HEAD"),argsOnly=true)
    private int siliconshaders$glow(int packedLight,@Local(argsOnly=true) ItemStack stack) { return SunHandLight.glow(stack,packedLight); }
}
