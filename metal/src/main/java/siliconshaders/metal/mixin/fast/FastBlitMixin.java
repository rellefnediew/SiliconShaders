package siliconshaders.metal.mixin.fast;

import com.mojang.blaze3d.vertex.VertexConsumer;
import siliconshaders.metal.fast.FastGui;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GUI image quads as whole-vertex writes (FastGui); vanilla bytes, fewer builder calls. */
@Mixin(BlitRenderState.class)
abstract class FastBlitMixin {
    @Inject(method="buildVertices",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$fastBlit(VertexConsumer consumer,CallbackInfo ci) {
        BlitRenderState self=(BlitRenderState)(Object)this;
        if(FastGui.blit(self.pose(),self.x0(),self.y0(),self.x1(),self.y1(),self.u0(),self.u1(),self.v0(),self.v1(),self.color(),consumer))ci.cancel();
    }
}
