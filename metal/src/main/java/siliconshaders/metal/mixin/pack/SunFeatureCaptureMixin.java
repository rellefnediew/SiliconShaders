package siliconshaders.metal.mixin.pack;

import java.util.List;
import siliconshaders.metal.sun.SunEntities;
import net.minecraft.client.renderer.feature.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTypeFeatureRenderer.class)
abstract class SunFeatureCaptureMixin {
    @Shadow @Final private List<?> groups;

    @Inject(method="prepareGroup",at=@At("RETURN"))
    private void siliconshaders$shadowGeometry(FeatureFrameContext context,List<?> submits,boolean ordered,CallbackInfo ci) {
        if(!SunEntities.collecting||!((Object)this instanceof ModelFeatureRenderer||(Object)this instanceof ItemFeatureRenderer))return;
        var group=(SunFeatureGroupAccess)groups.getLast();
        SunEntities.capture(context,group.siliconshaders$sunDraws(),group.siliconshaders$sunTypes());
    }
}
