package siliconshaders.metal.mixin.pack;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import siliconshaders.metal.sun.SunEntities;
import siliconshaders.metal.sun.SunLighting;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;

/** Shadow passes execute in the pre command buffer, so their shared vertices must be uploaded there first. */
@Mixin(StagedVertexBuffer.class)
abstract class SunStagedUploadMixin {
    @WrapMethod(method="upload")
    private void siliconshaders$shadowUploadOrder(Operation<Void> original) {
        boolean previous=SunEntities.uploading;
        SunEntities.uploading=RenderSystem.isRenderingLevel&&SunLighting.enabled();
        try { original.call(); }
        finally { SunEntities.uploading=previous; }
    }
}
