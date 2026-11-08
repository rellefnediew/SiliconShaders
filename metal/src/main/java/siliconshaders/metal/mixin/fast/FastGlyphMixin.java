package siliconshaders.metal.mixin.fast;

import com.mojang.blaze3d.vertex.VertexConsumer;
import siliconshaders.metal.fast.FastGui;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Text glyph quads as whole-vertex writes (FastGui); vanilla bytes, fewer builder calls. */
@Mixin(BakedSheetGlyph.class)
abstract class FastGlyphMixin {
    @Shadow @Final private float u0;
    @Shadow @Final private float u1;
    @Shadow @Final private float v0;
    @Shadow @Final private float v1;
    @Shadow @Final private float left;
    @Shadow @Final private float right;
    @Shadow @Final private float up;
    @Shadow @Final private float down;
    @Shadow private float shearTop() { throw new AssertionError(); }
    @Shadow private float shearBottom() { throw new AssertionError(); }

    @Inject(method="render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$fastGlyph(boolean italic,float x,float y,float z,Matrix4fc pose,VertexConsumer consumer,int color,boolean bold,int light,CallbackInfo ci) {
        if(FastGui.glyph(this.left,this.right,this.up,this.down,italic?this.shearTop():0,italic?this.shearBottom():0,this.u0,this.u1,this.v0,this.v1,
            x,y,z,pose,consumer,color,bold,light))ci.cancel();
    }
}
