package siliconshaders.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** the removed flag metal.atlasDouble: the texture and view everything samples (swapped between the atlas's two copies). */
@Mixin(AbstractTexture.class)
public interface AtlasDoubleTextureAccess {
	@Accessor("texture")
	void siliconshaders$adSetTexture(GpuTexture texture);

	@Accessor("textureView")
	void siliconshaders$adSetTextureView(GpuTextureView view);

	@Accessor("textureView")
	GpuTextureView siliconshaders$adTextureView();
}
