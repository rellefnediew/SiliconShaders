package siliconshaders.metal.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** the removed flag metal.atlasDouble: a sprite's padding (its animation quad is the sprite plus the padding on each side). */
@Mixin(TextureAtlasSprite.class)
public interface AtlasDoubleSpriteAccess {
	@Accessor("padding")
	int siliconshaders$adPadding();
}
