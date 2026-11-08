package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.vertex.QuadInstance;
import siliconshaders.metal.sun.SunLighting;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockModelLighter.class)
abstract class VanillaBlockShadeMixin {
    @Inject(method="prepareQuadAmbientOcclusion",at=@At("RETURN"))
    private void siliconshaders$plainAo(BlockAndTintGetter level,BlockState state,BlockPos pos,BakedQuad quad,QuadInstance out,CallbackInfo ci) {
        SunLighting.unshade(out);
    }
    @Inject(method="prepareQuadFlat",at=@At("RETURN"))
    private void siliconshaders$plainFlat(BlockAndTintGetter level,BlockState state,BlockPos pos,int light,BakedQuad quad,QuadInstance out,CallbackInfo ci) {
        SunLighting.unshade(out);
    }
}
