package siliconshaders.metal.bench;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit, cancellable feedback while a launcher finishes leaving the previous world. */
final class BenchStartScreen extends Screen {
    BenchStartScreen(){super(Component.translatable("siliconshaders.bench.preparing"));}
    @Override protected void init(){
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),b->onClose()).bounds(width/2-100,height/2+28,200,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){
        super.extractRenderState(g,x,y,tick);
        g.centeredText(font,title,width/2,height/2-30,0xffffffff);
        g.centeredText(font,ShaderBench.startMessage(),width/2,height/2-10,0xffcccccc);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ShaderBench.cancelPending();}
}
