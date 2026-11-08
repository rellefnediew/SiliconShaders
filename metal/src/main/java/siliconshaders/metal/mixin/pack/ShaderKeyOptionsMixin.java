package siliconshaders.metal.mixin.pack;

import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the shader key before the options file is read, so a player's own binding loads and is saved with the others. No Fabric API needed. */
@Mixin(Options.class)
abstract class ShaderKeyOptionsMixin {
    @Shadow @Final @Mutable public KeyMapping[] keyMappings;
    @Inject(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/Options;load()V"))
    private void siliconshaders$shaderKey(CallbackInfo ci) {
        keyMappings=Arrays.copyOf(keyMappings,keyMappings.length+1);
        keyMappings[keyMappings.length-1]=siliconshaders.metal.settings.ShaderKey.KEY;
    }
}
