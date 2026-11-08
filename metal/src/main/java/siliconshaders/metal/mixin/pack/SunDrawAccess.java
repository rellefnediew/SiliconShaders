package siliconshaders.metal.mixin.pack;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.Draw.class)
public interface SunDrawAccess {
    @Accessor("format") VertexFormat siliconshaders$sunFormat();
    @Accessor("vertexCount") int siliconshaders$sunVertexCount();
}
