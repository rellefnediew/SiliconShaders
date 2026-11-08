package siliconshaders.metal.mixin.own;

import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** the removed flag own.int.animCopy: the atlas's sprites, animation states, size and mip count. */
@Mixin(TextureAtlas.class)
public interface OwnAnimAtlasAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> siliconshaders$sprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> siliconshaders$animationStates();

	@Accessor("maxMipLevel")
	int siliconshaders$maxMipLevel();

	@Accessor("width")
	int siliconshaders$width();

	@Accessor("height")
	int siliconshaders$height();

	/** One-pass animation's fallback: vanilla's own upload of this tick's animation frames. */
	@org.spongepowered.asm.mixin.gen.Invoker("uploadAnimationFrames")
	void siliconshaders$uploadAnimationFrames();
}
