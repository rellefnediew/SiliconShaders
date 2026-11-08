package siliconshaders.metal.mixin.own;

import siliconshaders.metal.FrameLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dsiliconshaders.own.int.frameLog): the client tick's time in the frame it runs in. */
@Mixin(Minecraft.class)
public class FrameLogMinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void siliconshaders$frameLogTickHead(CallbackInfo ci) {
		FrameLog.begin(FrameLog.TICK);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void siliconshaders$frameLogTickReturn(CallbackInfo ci) {
		FrameLog.end(FrameLog.TICK);
	}
}
