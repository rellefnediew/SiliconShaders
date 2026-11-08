package siliconshaders.metal.mixin.pack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import siliconshaders.metal.sun.SunRain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;

/** Rain and snow (the non-OIT path) through SunRain's pipeline while the native shader is on; rain columns get SunRain's color. */
@Mixin(WeatherEffectRenderer.class)
abstract class SunRainMixin {
    @Redirect(method="render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at=@At(value="FIELD",target="Lnet/minecraft/client/renderer/RenderPipelines;WEATHER:Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;",opcode=Opcodes.GETSTATIC))
    private RenderPipeline siliconshaders$rainPipeline() { return SunRain.pipeline(RenderPipelines.WEATHER); }

    // Only the first invocation prepares rain. finally restores the context even on a failed/empty preparation.
    @WrapOperation(method="prepare",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/WeatherEffectRenderer;prepareInstances(Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;FIF)V",ordinal=0))
    private void siliconshaders$rainColumns(WeatherEffectRenderer renderer,VertexConsumer vertices,List<?> columns,Vec3 camera,float fade,int radius,float intensity,Operation<Void> original) {
        var mc=Minecraft.getInstance();var level=mc.level;float partial=mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        SunRain.beginRain(level==null?0:level.getThunderLevel(partial),level==null?0:level.getGameTime(),partial,camera);
        try { original.call(renderer,SunRain.vertices(vertices),columns,camera,fade,radius,intensity); }
        finally { SunRain.endRain(); }
    }
    @ModifyExpressionValue(method="prepareInstances",at=@At(value="INVOKE",target="Lnet/minecraft/util/ARGB;white(F)I"))
    private int siliconshaders$columnPosition(int vanilla,@Local(index=10) float x,@Local(index=11) float z) {
        // Keep vanilla's fade here. RainVertices applies tint and opacity once, after any Lunar weather color override.
        SunRain.columnPosition(x,z);return vanilla;
    }
}
