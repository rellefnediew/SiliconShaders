package siliconshaders.metal.mixin.pack;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's weather snippet (shaders, vertex format, blending), for our own weather pipeline (SunRain). */
@Mixin(RenderPipelines.class)
public interface SunRainPipelinesAccess {
    @Accessor("WEATHER_SNIPPET") static RenderPipeline.Snippet siliconshaders$weatherSnippet() { throw new AssertionError(); }
}
