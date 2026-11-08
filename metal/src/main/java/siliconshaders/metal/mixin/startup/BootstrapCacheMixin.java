package siliconshaders.metal.mixin.startup;

import siliconshaders.metal.Startup;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dsiliconshaders.startup.blockCache=...: right after FireBlock.bootStrap() (which first touches Blocks, so Blocks is initialised when it
 * returns; it only sets flammability, no state cache), the deferred state caches are built, before anything else in Bootstrap runs.
 */
@Mixin(Bootstrap.class)
abstract class BootstrapCacheMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V", shift = At.Shift.AFTER))
	private static void siliconshaders$caches(CallbackInfo ci) {
		Startup.finishBlockCaches();
	}
}
