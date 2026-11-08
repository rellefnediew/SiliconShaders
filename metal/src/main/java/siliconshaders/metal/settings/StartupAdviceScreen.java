package siliconshaders.metal.settings;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The start-up notice of StartupAdvice: what differs from the Standard profile's setup, with Apply (only when something
 * besides the MetalFX recommendation can be changed: the mod never switches MetalFX on), Later/OK and Don't show again.
 */
final class StartupAdviceScreen extends Screen {
    private static final int LINE=11;
    private final Screen parent;private final List<StartupAdvice.Item> items;
    private final List<FormattedCharSequence> intro=new ArrayList<>(),lines=new ArrayList<>();
    StartupAdviceScreen(Screen parent,List<StartupAdvice.Item> items){super(Component.translatable("siliconshaders.advice.title"));this.parent=parent;this.items=List.copyOf(items);}
    private int top(){return height/2-(20+(intro.size()+lines.size())*LINE+12+20)/2;}
    @Override protected void init(){
        int wrap=Math.max(120,Math.min(width-40,460));intro.clear();lines.clear();
        intro.addAll(font.split(Component.translatable("siliconshaders.advice.intro"),wrap));
        for(var item:items)lines.addAll(font.split(Component.literal("• ").append(item.text()),wrap));
        boolean apply=StartupAdvice.applicable(items);int buttons=apply?3:2,w=118,gap=6,x=width/2-(buttons*w+(buttons-1)*gap)/2;
        int y=top()+20+(intro.size()+lines.size())*LINE+12;
        if(apply){addRenderableWidget(Button.builder(Component.translatable("siliconshaders.advice.apply"),b->{StartupAdvice.apply(minecraft,items);onClose();}).bounds(x,y,w,20).build());x+=w+gap;}
        addRenderableWidget(Button.builder(Component.translatable(apply?"siliconshaders.advice.later":"siliconshaders.advice.ok"),b->onClose()).bounds(x,y,w,20).build());
        addRenderableWidget(Button.builder(Component.translatable("siliconshaders.advice.hide"),b->{StartupAdvice.hide();onClose();}).bounds(x+w+gap,y,w,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){
        super.extractRenderState(g,x,y,tick);
        int top=top();g.centeredText(font,title,width/2,top,0xffffffff);
        int at=top+20;
        for(var line:intro){g.centeredText(font,line,width/2,at,0xffcccccc);at+=LINE;}
        at+=4;
        for(var line:lines){g.centeredText(font,line,width/2,at,0xffffd27a);at+=LINE;}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}
