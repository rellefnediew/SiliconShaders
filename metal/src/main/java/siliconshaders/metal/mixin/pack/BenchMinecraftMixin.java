package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.bench.ShaderBench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The shader benchmark's clock: one call per frame, and the scripted camera right before the frame renders. */
@Mixin(Minecraft.class)
abstract class BenchMinecraftMixin {
    @Inject(method="runTick",at=@At("HEAD"))
    private void siliconshaders$benchFrame(boolean advanceGameTime,CallbackInfo ci) { ShaderBench.frame((Minecraft)(Object)this); }
    @Inject(method="renderFrame",at=@At("HEAD"))
    private void siliconshaders$benchPose(boolean advanceGameTime,CallbackInfo ci) { if(ShaderBench.active())ShaderBench.beforeRender((Minecraft)(Object)this); }
}
