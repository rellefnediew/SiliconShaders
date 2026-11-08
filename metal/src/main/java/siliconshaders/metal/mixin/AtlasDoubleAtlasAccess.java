package siliconshaders.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** the removed flag metal.atlasDouble: the atlas's sprites, animation states, per-mip views (the animation passes' targets) and size. */
@Mixin(TextureAtlas.class)
public interface AtlasDoubleAtlasAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> siliconshaders$adSprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> siliconshaders$adStates();

	@Accessor("mipViews")
	GpuTextureView[] siliconshaders$adMipViews();

	@Accessor("mipViews")
	void siliconshaders$adSetMipViews(GpuTextureView[] views);

	@Accessor("maxMipLevel")
	int siliconshaders$adMaxMip();

	@Accessor("width")
	int siliconshaders$adWidth();

	@Accessor("height")
	int siliconshaders$adHeight();
}
