package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import siliconshaders.metal.bench.ShaderBench;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** During the shader benchmark no AFK, menu or unfocused-window throttling: always unlimited, regardless of the player's option. */
@Mixin(FramerateLimitTracker.class)
abstract class BenchFramerateMixin {
    @Inject(method="getFramerateLimit",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$benchUnthrottled(CallbackInfoReturnable<Integer> cir) { if(ShaderBench.active())cir.setReturnValue(Integer.MAX_VALUE); }
}
