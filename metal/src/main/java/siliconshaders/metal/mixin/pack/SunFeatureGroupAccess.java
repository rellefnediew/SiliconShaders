package siliconshaders.metal.mixin.pack;

import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public interface SunFeatureGroupAccess {
    @Accessor("draws") List<StagedVertexBuffer.Draw> siliconshaders$sunDraws();
    @Accessor("drawRenderTypes") List<PreparedRenderType> siliconshaders$sunTypes();
}
