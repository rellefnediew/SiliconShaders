package siliconshaders.metal.mixin.pack;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import java.util.List;
import java.util.Optional;
import siliconshaders.metal.MetalBridge;
import siliconshaders.metal.own.RenderScale;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Match both frontend metadata and the Metal state before the frontend validates the HDR world format. */
@Mixin(FrontendRenderPass.class)
abstract class HdrRenderPassMixin {
    @Shadow @Final private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

    @ModifyVariable(method="setPipeline",at=@At("HEAD"),argsOnly=true)
    private CompiledRenderPipeline siliconshaders$hdrPipeline(CompiledRenderPipeline pipeline) {
        if(colorAttachments.size()==1) {
            var color=colorAttachments.getFirst();
            if(color!=null&&RenderScale.isHdrTarget(color.textureView().texture()))return MetalBridge.hdrPipeline(pipeline);
        }
        return pipeline;
    }
}
