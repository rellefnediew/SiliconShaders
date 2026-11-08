package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.bench.ShaderBench;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Benchmark progress stays visible when the ordinary HUD is hidden. */
@Mixin(Gui.class)
abstract class BenchGuiMixin {
    @Shadow @Final private GuiRenderState guiRenderState;
    @Inject(method="extractRenderState",at=@At("RETURN"))
    private void siliconshaders$benchmarkProgress(CallbackInfo ci){
        if(!ShaderBench.active())return;
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.gui.screen()!=null)return;
        var g=new GuiGraphicsExtractor(mc,guiRenderState,0,0);g.nextStratum();
        String line=mc.font.plainSubstrByWidth(ShaderBench.progress(),g.guiWidth()-16);
        g.fill(4,4,Math.min(g.guiWidth()-4,mc.font.width(line)+12),20,0xd9111922);
        g.text(mc.font,line,8,8,0xffeeeeee,false);
    }
}
