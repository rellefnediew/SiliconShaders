package siliconshaders.metal.mixin.pack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.class)
public interface SunFeatureDispatcherAccess {
    @Accessor("modelManager") ModelManager siliconshaders$sunModels();
    @Accessor("atlasManager") AtlasManager siliconshaders$sunAtlases();
    @Accessor("font") Font siliconshaders$sunFont();
    @Accessor("gameRenderState") GameRenderState siliconshaders$sunGameState();
    @Mutable @Accessor("stagedVertexBuffer") void siliconshaders$sunStaging(StagedVertexBuffer buffer);
}
