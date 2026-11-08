package siliconshaders.metal.settings;

import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Flat, native-focus widgets. Geometry is batched by Minecraft; no browser or custom renderer; Screen owns the single menu-blur request. */
final class MenuWidgets {
    static final int BG=0x96161b20,SIDEBAR=0x881b2127,SURFACE=0xad252d35,HOVER=0xd1303b44,LINE=0x8f667581,TEXT=0xffe8edf0,MUTED=0xffb1bcc5,ACCENT=0xffa3c9b8,SELECTED=0xc52d403b,ERROR=0xffedb3a9,WARNING=0xffe8c37a;
    private MenuWidgets(){}
    /** The text, or as much as fits followed by an ellipsis: a cut label must not just lose its last letters. */
    static String fit(String text,int width){
        var font=Minecraft.getInstance().font;if(font.width(text)<=width)return text;
        String dots="…";return font.plainSubstrByWidth(text,Math.max(0,width-font.width(dots))).stripTrailing()+dots;
    }
    /** Word wrap into at most maxLines lines; the last one ends with an ellipsis when text remains. */
    static java.util.List<String> wrap(String text,int width,int maxLines){
        var font=Minecraft.getInstance().font;java.util.List<String> lines=new java.util.ArrayList<>();String rest=text.strip();
        while(!rest.isEmpty()&&lines.size()<maxLines){
            if(lines.size()==maxLines-1||font.width(rest)<=width){lines.add(fit(rest,width));break;}
            String part=font.plainSubstrByWidth(rest,width);int space=part.lastIndexOf(' ');if(space>0)part=part.substring(0,space);
            lines.add(part);rest=rest.substring(part.length()).strip();
        }
        return lines;
    }
    static void round(GuiGraphicsExtractor g,int x,int y,int w,int h,int color){
        if(w<=0||h<=0)return;
        if(w<5||h<5){g.fill(x,y,x+w,y+h,color);return;}
        // Non-overlapping strips: translucent fills must not accumulate three times in the centre.
        g.fill(x+2,y,x+w-2,y+1,color);g.fill(x+1,y+1,x+w-1,y+2,color);
        g.fill(x,y+2,x+w,y+h-2,color);
        g.fill(x+1,y+h-2,x+w-1,y+h-1,color);g.fill(x+2,y+h-1,x+w-2,y+h,color);
    }
    static final class Action extends Button {
        private final BooleanSupplier selected;private boolean primary;
        Action(int x,int y,int w,int h,Component label,Runnable action){this(x,y,w,h,label,action,()->false,false);}
        Action(int x,int y,int w,int h,Component label,Runnable action,BooleanSupplier selected,boolean primary){super(x,y,w,h,label,b->action.run(),DEFAULT_NARRATION);this.selected=selected;this.primary=primary;}
        void primary(boolean value){primary=value;}
        @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage(){var result=super.createNarrationMessage();if(selected.getAsBoolean())result.append(" ").append(Component.translatable("siliconshaders.menu.selected"));return result;}
        @Override protected void extractContents(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
            int fill=selected.getAsBoolean()?SELECTED:primary?0xffaacdbd:isHoveredOrFocused()?HOVER:SURFACE;
            if(isFocused()){round(g,getX()-1,getY()-1,width+2,height+2,ACCENT);}
            round(g,getX(),getY(),width,height,fill);if(selected.getAsBoolean())round(g,getX()+4,getY()+height/2-1,3,3,ACCENT);var font=Minecraft.getInstance().font;
            int color=!active?0xff7e8b94:primary?0xff18271f:selected.getAsBoolean()?ACCENT:TEXT;
            String text=fit(getMessage().getString(),width-12);
            g.text(font,text,getX()+(width-font.width(text))/2,getY()+(height-9)/2,color,false);
        }
    }
    /** A small square button with one symbol: too narrow for Action's margins. */
    static final class Icon extends Button {
        private final Component symbol;
        Icon(Component symbol,Runnable action){this(symbol,symbol,action);}
        Icon(Component symbol,Component label,Runnable action){super(0,0,16,16,label,b->action.run(),DEFAULT_NARRATION);this.symbol=symbol;}
        @Override protected void extractContents(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
            if(isHoveredOrFocused())round(g,getX(),getY(),width,height,HOVER);var font=Minecraft.getInstance().font;String text=symbol.getString();
            g.text(font,text,getX()+(width-font.width(text))/2,getY()+(height-9)/2,ACCENT,false);
        }
    }
    static final class Toggle extends Button {
        private final BooleanSupplier on;private final Component label;
        Toggle(int width,Component label,BooleanSupplier on,Runnable action){super(0,0,width,20,label,b->action.run(),DEFAULT_NARRATION);this.label=label;this.on=on;}
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float tick){
            boolean enabled=on.getAsBoolean();setMessage(label.copy().append(": ").append(Component.translatable(enabled?"options.on":"options.off")));
            if(isFocused())round(g,getX()-1,getY()-1,width+2,height+2,ACCENT);
            round(g,getX(),getY(),width,height,isHoveredOrFocused()?HOVER:SURFACE);
            var font=Minecraft.getInstance().font;int y=getY()+(height-9)/2;g.text(font,fit(label.getString(),width-56),getX()+6,y,active?TEXT:0xff7e8b94,false);
            int x=getRight()-37,sy=getY()+(height-11)/2;round(g,x,sy,31,11,enabled?SELECTED:0xff1a2026);
            round(g,x+(enabled?20:3),sy+2,8,7,active?(enabled?ACCENT:MUTED):0xff64717a);
        }
    }
    static final class Slider extends AbstractSliderButton {
        private final GraphicsCatalog.Rule rule;private final MenuSession session;private final Runnable checkpoint,changed;
        private final Function<Float,String> format;private final Component label;private final Consumer<Slider> holding;private final Runnable edit;
        Slider(int width,GraphicsCatalog.Rule rule,Component label,MenuSession session,Runnable checkpoint,Runnable changed,Function<Float,String> format,Consumer<Slider> holding,Runnable edit){
            super(0,0,width,22,Component.empty(),(session.value(rule.key())-rule.min())/(rule.max()-rule.min()));this.rule=rule;this.session=session;this.checkpoint=checkpoint;this.changed=changed;this.format=format;this.label=label;this.holding=holding;this.edit=edit;updateMessage();
        }
        void sync(){value=(session.value(rule.key())-rule.min())/(rule.max()-rule.min());updateMessage();}
        @Override protected void updateMessage(){if(rule!=null)setMessage(label.copy().append(": ").append(format.apply(session.value(rule.key()))));}
        @Override protected void applyValue(){if(rule==null)return;session.set(rule.key(),Math.clamp(rule.min()+(float)value*(rule.max()-rule.min()),rule.min(),rule.max()));updateMessage();changed.run();}
        @Override public void onClick(MouseButtonEvent event,boolean twice){
            if(edit!=null&&event.y()<getY()+13&&event.x()>getRight()-70){edit.run();return;}
            checkpoint.run();super.onClick(event,twice);holding.accept(this);
        }
        @Override public void onRelease(MouseButtonEvent event){super.onRelease(event);holding.accept(null);}
        void endHold(){holding.accept(null);}
        @Override public boolean keyPressed(KeyEvent event){
            if(event.isLeft()||event.isRight()){
                checkpoint.run();setValue(value+(event.isRight()?1:-1)*rule.step()/(rule.max()-rule.min()));return true;
            }return super.keyPressed(event);
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
            sync();var font=Minecraft.getInstance().font;int color=active?TEXT:0xff7e8b94;
            String number=format.apply(session.value(rule.key()));int numberWidth=font.width(number);
            g.text(font,fit(label.getString(),width-numberWidth-14),getX()+2,getY()+2,color,false);
            g.text(font,number,getRight()-numberWidth-2,getY()+2,active?(GraphicsCatalog.extreme(rule.key(),session.value(rule.key()))?WARNING:ACCENT):color,false);
            round(g,getX(),getY()+15,width,3,LINE);round(g,getX(),getY()+15,Math.max(1,(int)Math.round(value*(width-1))),3,active?ACCENT:0xff64717a);
            int at=getX()+4+(int)Math.round(value*(width-8));if(isFocused())round(g,at-4,getY()+11,8,10,ACCENT);round(g,at-2,getY()+12,4,8,active?TEXT:MUTED);
        }
    }
}
