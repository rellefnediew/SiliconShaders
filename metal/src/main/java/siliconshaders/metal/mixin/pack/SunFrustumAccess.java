package siliconshaders.metal.mixin.pack;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Frustum.class)
public interface SunFrustumAccess {
    @Accessor("matrix") Matrix4f siliconshaders$sunClip();
    @Accessor("camX") double siliconshaders$sunX();
    @Accessor("camY") double siliconshaders$sunY();
    @Accessor("camZ") double siliconshaders$sunZ();
}
