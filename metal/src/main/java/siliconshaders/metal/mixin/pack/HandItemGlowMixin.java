package siliconshaders.metal.mixin.pack;

import com.llamalad7.mixinextras.sugar.Local;
import siliconshaders.metal.sun.SunHandLight;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** First person: a held light-emitting item (and the hand holding it) is drawn glowing (SunHandLight.glow). */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class HandItemGlowMixin {
    @ModifyVariable(method="submitArmWithItem",at=@At("HEAD"),argsOnly=true)
    private int siliconshaders$glow(int packedLight,@Local(argsOnly=true) ItemStack stack) { return SunHandLight.glow(stack,packedLight); }
}
