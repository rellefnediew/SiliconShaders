package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Items switch render types quad by quad, and only one consecutive type is remembered: every switch prepares the type again
 * (new objects, textures, a transform lookup) just to find the draw it already has. A few types make up a group, so remember those.
 * A prepared type depends on the type, the model view and the scissor; a change of either of the last two forgets everything.
 */
@Mixin(targets="net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
abstract class FeatureGroupDrawCacheMixin {
    @Unique private static final int SILICONSHADERS_TYPES=8;
    @Shadow @Final private boolean canReorder;
    @Unique private final RenderType[] siliconshaders$types=new RenderType[SILICONSHADERS_TYPES];
    @Unique private final StagedVertexBuffer.Draw[] siliconshaders$draws=new StagedVertexBuffer.Draw[SILICONSHADERS_TYPES];
    @Unique private int siliconshaders$count;
    @Unique private final Matrix4f siliconshaders$modelView=new Matrix4f();
    @Unique private ScissorState siliconshaders$scissor;

    @Inject(method="getOrAddDraw",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$knownDraw(RenderType type,CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
        if(!canReorder||siliconshaders$count==0||!type.canConsolidateConsecutiveGeometry())return;
        if(!siliconshaders$modelView.equals(RenderSystem.getModelViewStack())||!RenderSystem.getScissorStateForRenderTypeDraws().equals(siliconshaders$scissor)){siliconshaders$count=0;return;}
        for(int i=0;i<siliconshaders$count;i++)if(siliconshaders$types[i]==type){cir.setReturnValue(siliconshaders$draws[i]);return;}
    }
    @Inject(method="getOrAddDraw",at=@At("RETURN"))
    private void siliconshaders$rememberDraw(RenderType type,CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
        if(!canReorder||siliconshaders$count==SILICONSHADERS_TYPES||!type.canConsolidateConsecutiveGeometry())return;
        if(siliconshaders$count==0){siliconshaders$modelView.set(RenderSystem.getModelViewStack());siliconshaders$scissor=new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());}
        siliconshaders$types[siliconshaders$count]=type;siliconshaders$draws[siliconshaders$count++]=cir.getReturnValue();
    }
}
