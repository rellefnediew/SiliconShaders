package siliconshaders.metal.mixin.pack;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where Minecraft handles its own keys: in the world, not while a menu is open or a chat is being typed. */
@Mixin(Minecraft.class)
abstract class ShaderKeyMixin {
    @Inject(method="handleKeybinds",at=@At("HEAD"))
    private void siliconshaders$shaderKey(CallbackInfo ci) { siliconshaders.metal.settings.ShaderKey.handle((Minecraft)(Object)this); }
}
