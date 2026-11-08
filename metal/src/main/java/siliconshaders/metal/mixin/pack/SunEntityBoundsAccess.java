package siliconshaders.metal.mixin.pack;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderer.class)
public interface SunEntityBoundsAccess {
    @Invoker("getBoundingBoxForCulling") AABB siliconshaders$sunBounds(Entity entity,float partialTick);
}
