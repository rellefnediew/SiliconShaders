package siliconshaders.metal.settings;

import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import static siliconshaders.metal.settings.MenuWidgets.*;

/**
 * Two views. Select: players pick a shader pack, a quality level and brightness. Edit: creators pick a feature on the left
 * and find every setting of that feature on the right (look, quality, more, developer). Edits are live previews; Done
 * saves, leaving with changes asks first.
 */
public final class GraphicsScreen extends Screen {
    private static final java.net.URI SUPPORT_URL=java.net.URI.create("https://ko-fi.com/kela12");
    private enum Page { MAIN, MANAGE, NAME, SHARE, IMPORT, COLOR, NUMBER, CONFIRM, BENCHMARK }
    private static final String[] LEVELS={"standard","low","medium","high","ultra"};
    /** Stands in for a label's %s: the text before it names the setting, the text after it is the value's unit. */
    private static final String VALUE_MARK="\uE000";
    private static boolean editing,developer,changedOnly;private static String lastFeature="picture";
    private final Screen parent;private final GraphicsSettings runtime;private final ProfileStore profiles;private final MenuSession session;
    private final List<GuiEventListener> body=new ArrayList<>();private final List<Runnable> refreshers=new ArrayList<>();
    private final List<GraphicsProfile> presets;private final Set<String> expanded=new HashSet<>();
    private final Map<String,Double> scroll=new HashMap<>();private final Map<String,String> units=new HashMap<>();private String scrollKey="";private double featureScroll;private int packPage;
    private Page page=Page.MAIN;private String feature=lastFeature,query="",notice="",warning="",shareCode="",editId="",colorBase="",numberKey="",info="";private boolean preview,comparing,closed,revealSelected=true;
    private GraphicsProfile pendingImport;private GraphicsProfile.Scope shareScope=GraphicsProfile.Scope.LOOK;
    private EditBox search,nameField,codeField,hexField;private ControlList controls;private FeatureList features;
    private Action undoButton;private MenuWidgets.Slider heldSlider;private int bx,bw,top,bottom;
    public GraphicsScreen(Screen parent){
        super(Component.translatable("siliconshaders.menu.title"));this.parent=parent;runtime=GraphicsSettings.live();profiles=new ProfileStore(runtime.directory());session=new MenuSession(runtime,profiles.active());presets=presets();
        if(!profiles.issue().isEmpty())notice=profiles.issue();
    }
    private static Component text(String key,Object... args){return Component.translatable("siliconshaders.menu."+key,args);}
    private Component featureTitle(String id){return text("feature."+id);}
    private List<GraphicsProfile> presets(){
        return BundledProfiles.all(text("preset.standard").getString());
    }
    private GraphicsProfile selected(){GraphicsProfile own=profiles.find(session.selected());if(own!=null)return own;return presets.stream().filter(p->p.id().equals(session.selected())).findFirst().orElse(null);}

    // ---- layout ----
    @Override protected void init(){
        body.clear();search=null;
        top=68;bottom=height-30;
        // Separate brand row and navigation row keep the full name and icon clear even at a 320px GUI width.
        addRenderableWidget(new Action(12,34,96,18,text("view.select"),()->{editing=false;page=Page.MAIN;rebuildAll();},()->!editing&&page==Page.MAIN,false));
        addRenderableWidget(new Action(112,34,96,18,text("view.edit"),()->{editing=true;page=Page.MAIN;rebuildAll();},()->editing&&page==Page.MAIN,false));
        Toggle master=addRenderableWidget(new Toggle(110,text("shader"),()->session.value("shadow.enabled")>.5f,()->{session.checkpoint();session.set("shadow.enabled",session.value("shadow.enabled")>.5f?0:1);}));
        master.setPosition(width-140,7);master.setTooltip(Tooltip.create(text("shader.help")));
        addRenderableWidget(new Action(width-26,8,18,18,Component.literal("×"),this::onClose)).setTooltip(Tooltip.create(text("cancel.help")));
        int searchX=216,searchW=width-12-searchX;
        if(editing&&searchW>=60){
            search=addRenderableWidget(new EditBox(font,searchX+5,38,searchW-10,12,text("search")));search.setBordered(false);search.setTextShadow(false);search.setTextColor(TEXT);search.setHint(text("search"));search.setValue(query);search.setMaxLength(80);
            search.setResponder(value->{if(value.equals(query))return;query=value;page=Page.MAIN;rebuild();});
        }
        boolean compactFooter=width<438;
        AbstractWidget support;
        if(compactFooter){support=new Icon(Component.literal("♥"),text("support"),this::support);support.setRectangle(18,18,12,height-24);}
        else support=new Action(12,height-24,108,18,text("support"),this::support);
        addRenderableWidget(support).setTooltip(Tooltip.create(text("support.help")));
        int undoX=compactFooter?34:126,previewX=compactFooter?96:200,actionWidth=compactFooter?58:70;
        undoButton=addRenderableWidget(new Action(undoX,height-24,actionWidth,18,text("undo"),()->{session.undo();rebuild();}));undoButton.setTooltip(Tooltip.create(text("undo.help")));
        Action see=addRenderableWidget(new Action(previewX,height-24,actionWidth,18,text("preview"),()->{preview=true;comparing=false;}));see.active=minecraft.level!=null;see.setTooltip(Tooltip.create(text("preview.help")));
        addRenderableWidget(new Action(width-162,height-24,82,18,text("benchmark"),()->open(Page.BENCHMARK)));
        addRenderableWidget(new Action(width-74,height-24,62,18,text("done"),this::finish,()->false,true));
        rebuild();
    }
    // Switching views changes the top bar (search only while editing).
    private void rebuildAll(){warning="";clearWidgets();init();}
    private void rebuild(){
        // A rebuild keeps each list where it was: opening "more", undo or a colour page must not jump back to the top.
        if(controls!=null)scroll.put(scrollKey,controls.scrollAmount());if(features!=null)featureScroll=features.scrollAmount();
        for(GuiEventListener w:body){if(getFocused()==w)clearFocus();removeWidget(w);}body.clear();refreshers.clear();controls=null;features=null;nameField=codeField=hexField=null;info="";
        if(page!=Page.MAIN){bx=12;bw=width-24;switch(page){case MANAGE->manage();case NAME->nameForm();case SHARE->share();case IMPORT->importForm();case COLOR->color();case NUMBER->number();case CONFIRM->confirm();case BENCHMARK->benchmark();default->{}}}
        else if(editing)editView();else selectView();
        scrollKey=page+"/"+(editing?feature:"")+"/"+query+"/"+changedOnly;
        if(controls!=null)controls.setScrollAmount(scroll.getOrDefault(scrollKey,0d));if(features!=null)features.setScrollAmount(featureScroll);
        if(undoButton!=null)undoButton.active=session.canUndo();
    }
    private <T extends AbstractWidget> T body(T widget){body.add(widget);return addRenderableWidget(widget);}

    // ---- select view: pick a pack, a quality level, brightness ----
    private int packColumns(){return Math.max(2,Math.min(5,bw/130));}
    private int packRows(int packs){return Math.max(1,Math.min((bottom-top-128)/36,(packs+packColumns()-1)/packColumns()));}
    private void selectView(){
        bx=12;bw=width-24;
        List<GraphicsProfile> packs=new ArrayList<>(presets);packs.addAll(profiles.profiles());
        int columns=packColumns(),gap=6,cw=(bw-gap*(columns-1))/columns,ch=30,rows=packRows(packs.size());int at=0;
        // More packs than fit: the last card turns the page.
        int capacity=rows*columns;boolean paged=packs.size()>capacity;if(paged)capacity--;
        int pages=paged?(packs.size()+capacity-1)/capacity:1;
        if(revealSelected){revealSelected=false;for(int i=0;i<packs.size();i++)if(packs.get(i).id().equals(session.selected()))packPage=i/capacity;}
        packPage=Math.floorMod(packPage,pages);
        for(var pack:packs.subList(packPage*capacity,Math.min(packs.size(),(packPage+1)*capacity))){
            int x=bx+(at%columns)*(cw+gap),y=top+12+(at/columns)*(ch+gap);at++;
            Action card=body(new Action(x,y,cw,ch,Component.literal(pack.name()),()->{try{session.replace(pack);notice="";warning=pack.id().startsWith("builtin.")?"":fitWarning(pack,"profile.older");rebuild();}catch(Exception failure){error(failure);}},()->session.selected().equals(pack.id()),false));
            card.setTooltip(Tooltip.create(text(pack.id().startsWith("builtin.")?"pack.builtin":"pack.own",pack.name())));
        }
        if(paged){int x=bx+(at%columns)*(cw+gap),y=top+12+(at/columns)*(ch+gap);at++;body(new Action(x,y,cw,ch,text("pack.more",packPage+1,pages),()->{packPage++;rebuild();}));}
        int q=top+12+rows*(ch+gap)+18,segment=Math.min(90,(bw-gap*(LEVELS.length-1))/LEVELS.length),wide=Math.min(bw,LEVELS.length*segment+(LEVELS.length-1)*gap);
        for(int i=0;i<LEVELS.length;i++){
            int level=i;Action button=body(new Action(bx+i*(segment+gap),q,segment,20,text("quality."+LEVELS[i]),()->{session.setAll(GraphicsCatalog.QUALITY_LEVELS.get(level));rebuild();},()->qualityLevel()==level,false));
            button.setTooltip(Tooltip.create(text("quality."+LEVELS[i]+".help")));
        }
        var exposure=GraphicsCatalog.RULES.get("look.exposure");
        MenuWidgets.Slider brightness=body(new MenuWidgets.Slider(wide,exposure,text("brightness"),session,session::checkpoint,()->{},v->value(exposure,v),this::hold,null));
        brightness.setPosition(bx,q+34);
        int tool=(wide-3*gap)/4;
        body(new Action(bx,q+66,tool,20,text("pack.edit"),()->{editing=true;rebuildAll();})).setTooltip(Tooltip.create(text("pack.edit.help")));
        body(new Action(bx+tool+gap,q+66,tool,20,text("pack.copy"),this::openShare)).setTooltip(Tooltip.create(text("share.help")));
        body(new Action(bx+2*(tool+gap),q+66,tool,20,text("pack.import"),()->open(Page.IMPORT))).setTooltip(Tooltip.create(text("import.help")));
        body(new Action(bx+3*(tool+gap),q+66,tool,20,text("pack.manage"),()->open(Page.MANAGE))).setTooltip(Tooltip.create(text("pack.manage.help")));
    }
    private int qualityLevel(){
        for(int i=0;i<GraphicsCatalog.QUALITY_LEVELS.size();i++){
            boolean all=true;
            for(var e:GraphicsCatalog.QUALITY_LEVELS.get(i).entrySet()){var r=GraphicsCatalog.RULES.get(e.getKey());if(r!=null&&Math.abs(session.value(e.getKey())-Math.clamp(e.getValue(),r.min(),r.max()))>.0001f){all=false;break;}}
            if(all)return i;
        }return -1;
    }

    // ---- edit view: features on the left, everything of the selected feature on the right ----
    private void editView(){
        int fw=Math.clamp(width/4,110,150);bx=12+fw+10;bw=width-12-bx;
        features=body(new FeatureList(12,top,fw,bottom-top-50));
        String area="";for(var f:GraphicsCatalog.FEATURES){if(!f.area().equals(area)){area=f.area();features.add(new FeatureRow(text("area."+area)));}features.add(new FeatureRow(f));}
        Toggle dev=body(new Toggle(fw,text("developer"),()->developer,()->{developer=!developer;rebuild();}));dev.setPosition(12,bottom-22);dev.setTooltip(Tooltip.create(text("developer.help")));
        Toggle onlyChanged=body(new Toggle(fw,text("changedOnly"),()->changedOnly,()->{changedOnly=!changedOnly;rebuild();}));onlyChanged.setPosition(12,bottom-46);onlyChanged.setTooltip(Tooltip.create(text("changedOnly.help")));
        controls=body(new ControlList(bx,top+14,bw,bottom-top-30));
        if(!query.isBlank()||changedOnly){results();return;}
        var current=GraphicsCatalog.FEATURES.stream().filter(f->f.id().equals(feature)).findFirst().orElse(GraphicsCatalog.FEATURES.get(0));
        if(current.toggle()!=null)controls.add(row(GraphicsCatalog.RULES.get(current.toggle()),featureTitle(current.id())));
        Map<GraphicsCatalog.Tier,List<GraphicsCatalog.Rule>> tiers=new EnumMap<>(GraphicsCatalog.Tier.class);
        for(var rule:GraphicsCatalog.RULES.values())if(GraphicsCatalog.feature(rule).equals(current.id())&&!rule.key().equals(current.toggle()))tiers.computeIfAbsent(GraphicsCatalog.tier(rule),t->new ArrayList<>()).add(rule);
        tier(tiers,GraphicsCatalog.Tier.LOOK,"tier.look");tier(tiers,GraphicsCatalog.Tier.QUALITY,"tier.quality");
        var more=tiers.getOrDefault(GraphicsCatalog.Tier.MORE,List.of());
        if(!more.isEmpty()){
            boolean open=expanded.contains(current.id());
            controls.add(new ControlRow(new Action(0,0,bw-12,18,text(open?"tier.less":"tier.more",more.size()),()->{if(!expanded.remove(current.id()))expanded.add(current.id());rebuild();}),Component.empty(),()->true,""));
            if(open)tier(tiers,GraphicsCatalog.Tier.MORE,null);
        }
        if(developer)tier(tiers,GraphicsCatalog.Tier.DEVELOPER,"tier.developer");
        controls.add(new ControlRow(new Action(0,0,bw-12,18,text("resetFeature"),()->{
            Map<String,Float> neutral=new LinkedHashMap<>();for(var r:GraphicsCatalog.RULES.values())if(GraphicsCatalog.feature(r).equals(current.id()))neutral.put(r.key(),r.neutral());session.setAll(neutral);rebuild();
        }),text("resetFeature.help"),()->true,""));
    }
    private void tier(Map<GraphicsCatalog.Tier,List<GraphicsCatalog.Rule>> tiers,GraphicsCatalog.Tier tier,String heading){
        var rules=tiers.getOrDefault(tier,List.of());if(rules.isEmpty())return;
        if(heading!=null)controls.add(heading(text(heading)));
        // Grouped by what the settings belong to, not by their packet position; a group keeps its own order.
        var sorted=new ArrayList<>(rules);sorted.sort(Comparator.comparingInt(r->GraphicsCatalog.GROUP_ORDER.indexOf(r.group())));
        boolean several=!sorted.get(0).group().equals(sorted.get(sorted.size()-1).group());
        String last="";List<GraphicsCatalog.Rule> group=new ArrayList<>();
        for(var rule:sorted){
            if(several&&!rule.group().equals(last)){if(!group.isEmpty())rules(group);group=new ArrayList<>();last=rule.group();controls.add(new ControlRow(null,text("group."+last),()->false,""));}
            group.add(rule);
        }
        rules(group);
    }
    private void results(){
        List<GraphicsCatalog.Rule> found=new ArrayList<>();
        for(var rule:GraphicsCatalog.RULES.values()){
            String f=GraphicsCatalog.feature(rule);if(f.isEmpty())continue;
            // A changed developer value counts too: it is hidden, and it changes the picture all the same.
            if(changedOnly?!changed(rule.key()):!developer&&GraphicsCatalog.tier(rule)==GraphicsCatalog.Tier.DEVELOPER)continue;
            if(query.isBlank()||matches(rule,label(rule),f))found.add(rule);
        }
        found.sort(Comparator.comparingInt(r->featureIndex(GraphicsCatalog.feature(r))));
        String last="";List<GraphicsCatalog.Rule> group=new ArrayList<>();
        for(var rule:found){String f=GraphicsCatalog.feature(rule);if(!f.equals(last)){if(!group.isEmpty())rules(group);group=new ArrayList<>();last=f;controls.add(heading(featureTitle(f)));}group.add(rule);}
        if(!group.isEmpty())rules(group);
        if(found.isEmpty())controls.add(new ControlRow(null,text(changedOnly&&query.isBlank()?"noChanges":"noResults"),()->false,""));
    }
    private static int featureIndex(String id){for(int i=0;i<GraphicsCatalog.FEATURES.size();i++)if(GraphicsCatalog.FEATURES.get(i).id().equals(id))return i;return 99;}
    // Settings in order; an RGB triple becomes one colour row.
    private void rules(List<GraphicsCatalog.Rule> rules){
        Set<String> keys=new HashSet<>();for(var r:rules)keys.add(r.key());Set<String> skip=new HashSet<>();
        for(var rule:rules){
            if(skip.contains(rule.key()))continue;
            String stem=rule.key().endsWith("R")?rule.key().substring(0,rule.key().length()-1):"";
            if(!stem.isEmpty()&&keys.contains(stem+"G")&&keys.contains(stem+"B")){
                skip.add(stem+"G");skip.add(stem+"B");
                Action picker=new Action(0,0,bw-12,18,colorLabel(stem),()->{session.checkpoint();colorBase=stem;open(Page.COLOR);});
                controls.add(new ControlRow(picker,text("color.help"),()->available(rule.key()),stem,false,rule.key()));continue;
            }
            controls.add(row(rule,label(rule)));
        }
    }
    private ControlRow row(GraphicsCatalog.Rule rule,Component label){
        AbstractWidget widget;String key=rule.key();
        if(rule.toggle())widget=new Toggle(bw-12,label,()->session.value(key)>.5f,()->{session.checkpoint();session.set(key,session.value(key)>.5f?0:1);});
        else if(key.equals("shadow.size")||key.endsWith("Variant")||key.equals("shadow.gbufferView")||key.equals("sun.aurora")||key.equals("sun.rainbows")||key.endsWith("Quality")||key.equals("look.curve")||key.equals("sun.celestialSunShape")||key.equals("sun.celestialMoonShape")){
            Action choice=new Action(0,0,bw-12,18,label,()->cycle(rule));refreshers.add(()->choice.setMessage(label.copy().append(": ").append(value(rule,session.value(key)))));widget=choice;
        }else widget=new MenuWidgets.Slider(bw-12,rule,label,session,session::checkpoint,()->{},v->value(rule,v),this::hold,()->{numberKey=key;open(Page.NUMBER);});
        Component help=help(rule);widget.setTooltip(Tooltip.create(widget instanceof MenuWidgets.Slider?help.copy().append(" ").append(text("slider.help")):help));
        return new ControlRow(widget,help,()->available(key),"",false,key);
    }
    // A colour counts as one setting: changed if any channel is.
    private boolean changed(String key){
        String stem=GraphicsCatalog.colorStem(key);
        if(stem==null){var rule=GraphicsCatalog.RULES.get(key);return rule!=null&&GraphicsCatalog.differs(rule,session.value(key));}
        for(char c:"RGB".toCharArray())if(GraphicsCatalog.differs(GraphicsCatalog.RULES.get(stem+c),session.value(stem+c)))return true;
        return false;
    }
    private void resetRow(String key){
        session.checkpoint();String stem=GraphicsCatalog.colorStem(key);
        if(stem==null)session.set(key,GraphicsCatalog.RULES.get(key).neutral());
        else for(char c:"RGB".toCharArray())session.set(stem+c,GraphicsCatalog.RULES.get(stem+c).neutral());
        if(changedOnly)rebuild();
    }
    private int changedIn(String id){
        int count=0;for(var rule:GraphicsCatalog.rules(id)){String stem=GraphicsCatalog.colorStem(rule.key());if((stem==null||rule.key().endsWith("R"))&&changed(rule.key()))count++;}
        return count;
    }
    // The first switch that keeps this setting from working, followed up to the one that is actually off.
    private GraphicsCatalog.Rule blockedBy(String key){
        for(String need:GraphicsCatalog.requires(key)){if(session.value(need)<=0)return GraphicsCatalog.RULES.get(need);if(!available(need))return blockedBy(need);}
        return null;
    }
    private String hoverText(String key,Component help){
        if(!key.isEmpty()&&!available(key)){var by=blockedBy(key);return by==null?text("dependency").getString():text("dependency.needs",label(by)).getString();}
        if(key.isEmpty())return help.getString();
        String lead=GraphicsCatalog.saves(key)?text("cost.saves").getString():GraphicsCatalog.cost(key)>0?text("cost."+GraphicsCatalog.cost(key)).getString():"";
        var rule=GraphicsCatalog.RULES.get(key);if(rule!=null&&GraphicsCatalog.extreme(key,session.value(key)))lead=text("extreme").getString()+lead;
        return lead+help.getString();
    }
    private void leavePreview(){preview=false;if(comparing){comparing=false;session.showDraft();}}
    // Before and after: the same quality settings, so the difference on screen is the look alone.
    private void toggleComparison(){
        comparing=!comparing;
        if(!comparing){session.showDraft();return;}
        Map<String,Float> standard=new LinkedHashMap<>(session.values());
        for(var rule:GraphicsCatalog.RULES.values())if(rule.shareable()&&!rule.quality()&&!rule.key().equals("shadow.enabled"))standard.put(rule.key(),rule.neutral());
        session.show(standard);
    }
    private ControlRow heading(Component title){return new ControlRow(null,title,()->false,"",true);}
    private boolean matches(GraphicsCatalog.Rule rule,Component label,String f){String term=query.strip().toLowerCase(Locale.ROOT);return (label.getString()+" "+help(rule).getString()+" "+featureTitle(f).getString()).toLowerCase(Locale.ROOT).contains(term);}
    private String markedLabel(GraphicsCatalog.Rule rule){return Component.translatable(rule.label(),VALUE_MARK).getString();}
    private Component label(GraphicsCatalog.Rule rule){
        // With custom bodies the vanilla sun and moon are not drawn: only the stars are left for this one.
        if(rule.key().equals("sun.skyFeatures")&&session.value("sun.celestialStyle")>0)return Component.translatable("siliconshaders.shaders.sun.tune.skyFeatures.stars");
        String text=markedLabel(rule);int mark=text.indexOf(VALUE_MARK);String label=(mark<0?text:text.substring(0,mark)).strip();
        while(label.endsWith(":"))label=label.substring(0,label.length()-1).stripTrailing();return Component.literal(label);
    }
    private String unit(GraphicsCatalog.Rule rule){
        return units.computeIfAbsent(rule.key(),key->{String text=markedLabel(rule);int mark=text.indexOf(VALUE_MARK);return mark<0?"":text.substring(mark+VALUE_MARK.length()).strip();});
    }
    private Component help(GraphicsCatalog.Rule rule){String key=rule.label()+(rule.key().equals("sun.skyFeatures")&&session.value("sun.celestialStyle")>0?".stars.help":".help");String value=Component.translatable(key).getString();String f=GraphicsCatalog.feature(rule);return value.equals(key)?featureTitle(f.isEmpty()?"picture":f):Component.literal(value);}
    private Component colorLabel(String stem){return text("color."+stem.replace('.','_'));}
    private String value(GraphicsCatalog.Rule r,float v){
        String number=number(r,v),unit=unit(r);
        return unit.isEmpty()?number:unit.startsWith("×")?number+unit:number+" "+unit;
    }
    private String number(GraphicsCatalog.Rule r,float v){
        String key=r.key();
        if(key.equals("sun.weatherSun")||key.equals("sun.weatherMoon")||key.equals("sun.weatherThunder")||key.equals("sun.weatherThunderMoon")||key.equals("sun.rainDim")||key.equals("sun.weatherNightDim"))return Math.round(v*100)+" %";
        if(key.equals("shadow.size"))return v==0?Component.translatable("options.off").getString():Math.round(v)+" px";
        if(key.equals("shadow.gbufferView"))return Component.translatable("siliconshaders.shaders.sun.gbufferView."+Math.round(v)).getString();
        if(key.endsWith("raysVariant"))return Component.translatable("siliconshaders.camera.raysVariant."+(v>.5f?"optimized":"original")).getString();
        if(key.endsWith("bloomVariant"))return Component.translatable("siliconshaders.camera.bloomVariant."+(v>.5f?"hdr":"original")).getString();
        if(key.equals("sun.aurora"))return Component.translatable("siliconshaders.shaders.sun.tune.aurora.mode."+Math.round(v)).getString();
        if(key.equals("sun.rainbows"))return Component.translatable("siliconshaders.shaders.sun.tune.rainbows.mode."+Math.round(v)).getString();
        if(key.equals("look.curve"))return Component.translatable("siliconshaders.shaders.sun.look.curve."+Math.round(v)).getString();
        if(key.equals("sun.celestialSunShape")||key.equals("sun.celestialMoonShape"))return Component.translatable("siliconshaders.shaders.sun.tune.celestialShape."+Math.round(v)).getString();
        if(key.equals("sun.celestialSunSize")||key.equals("sun.celestialMoonSize"))return String.format(Locale.ROOT,"%.1f°",v);
        if(key.equals("sun.celestialMoonPhaseOffset"))return Math.round(v)+"°";
        if(key.equals("sun.nightStars")||key.equals("sun.celestialMoonPhaseSoftness")||key.equals("sun.endRingTransparency")||key.equals("sun.endFogStrength")||key.equals("sun.endFogBrightness")||key.equals("sun.endFogThickness")||key.equals("sun.endFogSize")||key.equals("sun.endFogWisps")||key.equals("sun.endFogSpeed")||key.equals("sun.endFogEmber")||key.equals("sun.endFogHoleLight")||key.equals("sun.endLightStrength")||key.equals("sun.endHoleSize")||key.equals("sun.endDiscBrightness")||key.equals("sun.endStars"))return Math.round(v*100)+" %";
        if(key.equals("look.autoExposure")&&v<=0)return Component.translatable("options.off").getString();
        if(key.equals("look.autoExposureRange"))return String.format(Locale.ROOT,"±%.2f EV",v);
        if(key.endsWith("Quality"))return Component.translatable("siliconshaders.shaders.sun.tune.quality."+Math.round(v)).getString();
        if(key.equals("sun.renderScale"))return Math.round(siliconshaders.metal.own.RenderScale.factor(Math.round(v))*100)+" %";
        if(key.equals("sun.materialWorldReflectionResolution"))return (100>>Math.round(v))+" %";
        if(key.endsWith("raysScale")||key.endsWith("bloomScale"))return Math.round(v*100)+" %";
        if(key.endsWith("Direction")||key.endsWith("Angle")||key.endsWith("Elevation")||key.endsWith("Extent"))return Math.round(v)+"°";
        return r.step()>=1?Integer.toString(Math.round(v)):String.format(Locale.ROOT,r.step()<.01?"%.3f":"%.2f",v);
    }
    private void cycle(GraphicsCatalog.Rule rule){
        session.checkpoint();float v=session.value(rule.key());
        if(rule.key().equals("shadow.size")){int[] sizes={0,512,1024,2048,4096};for(int i=0;i<sizes.length;i++)if(sizes[i]==v){session.set(rule.key(),sizes[(i+1)%sizes.length]);break;}}
        else session.set(rule.key(),v>=rule.max()?rule.min():v+rule.step());
    }
    // A setting is greyed out while a switch it depends on is off, directly or further up.
    private boolean available(String key){
        for(String need:GraphicsCatalog.requires(key))if(session.value(need)<=0||!available(need))return false;
        return true;
    }
    private boolean featureOn(GraphicsCatalog.Feature f){return f.toggle()==null||(session.value(f.toggle())>0&&available(f.toggle()));}

    // ---- pages ----
    private void open(Page next){page=next;notice="";warning="";pendingImport=null;rebuild();}
    private void back(){page=Page.MAIN;notice="";warning="";rebuild();}
    private void name(String id){editId=id;open(Page.NAME);}
    private EditBox field(int y,Component hint,int max){EditBox box=body(new EditBox(font,bx+7,y,bw-14,20,hint));box.setBordered(false);box.setTextShadow(false);box.setTextColor(TEXT);box.setHint(hint);box.setMaxLength(max);return box;}
    private String copyName(){GraphicsProfile profile=selected();return text("profile.copyName",profile==null?text("defaultName").getString():profile.name()).getString();}
    private void saveProfile(){
        GraphicsProfile existing=profiles.find(session.selected());
        if(existing==null&&!session.builtIn()){name("");return;}
        try{
            if(existing==null)session.saveCopy(profiles,copyName());
            else session.saveProfile(profiles,session.snapshot(existing.id(),existing.name(),existing.scope()));
            revealSelected=true;back();notice="saved";
        }catch(Exception failure){error(failure);}
    }
    private void manage(){
        controls=body(new ControlList(bx,top+14,bw,bottom-top-30));
        controls.add(new ControlRow(new Action(0,0,bw-12,18,text("newProfile"),()->name("")),text("newProfile.help"),()->true,""));
        controls.add(new ControlRow(new Action(0,0,bw-12,18,text("saveProfile"),this::saveProfile),text("saveProfile.help"),()->profiles.issue().isEmpty(),""));
        controls.add(new ControlRow(new Action(0,0,bw-12,18,text("share"),this::openShare),text("share.help"),()->true,""));
        if(!profiles.profiles().isEmpty())controls.add(heading(text("myProfiles")));
        for(var p:profiles.profiles())controls.add(new ControlRow(new Action(0,0,bw-12,18,Component.literal(p.name()),()->name(p.id())),text("manage.help"),()->true,""));
        if(profiles.profiles().isEmpty())controls.add(new ControlRow(null,text("emptyProfiles"),()->false,""));
    }
    private void nameForm(){
        GraphicsProfile existing=profiles.find(editId);nameField=field(top+16,text("profileName"),96);nameField.setValue(existing==null?text("defaultName").getString():existing.name());setInitialFocus(nameField);
        body(new Action(bx,top+44,bw,20,text(existing==null?"create":"rename"),()->{
            try{
                String id=existing==null?UUID.randomUUID().toString():existing.id();GraphicsProfile p=existing==null?session.snapshot(id,nameField.getValue(),GraphicsProfile.Scope.ALL):existing.renamed(nameField.getValue());
                if(existing==null){session.saveProfile(profiles,p);revealSelected=true;}else profiles.put(p);
                back();notice="saved";
            }catch(Exception failure){error(failure);}
        },()->false,true));
        if(existing!=null){
            body(new Action(bx,top+70,(bw-6)/2,20,text("duplicate"),()->{try{GraphicsCatalog.merge(session.values(),existing);var p=new GraphicsProfile(UUID.randomUUID().toString(),existing.name()+" (2)",existing.scope(),existing.values());profiles.put(p);session.replace(p);open(Page.MANAGE);}catch(Exception failure){error(failure);}}));
            body(new Action(bx+(bw+6)/2,top+70,(bw-6)/2,20,text("delete"),()->{
                if(!notice.equals("delete.confirm")){notice="delete.confirm";return;}
                try{profiles.delete(existing.id());if(session.selected().equals(existing.id()))session.select("");open(Page.MANAGE);notice="deleted";}catch(Exception failure){error(failure);}
            }));
        }
    }
    // Asking for the code puts it on the clipboard at once; choosing the other scope replaces it there.
    private void openShare(){open(Page.SHARE);copyCode();}
    private void copyCode(){minecraft.keyboardHandler.setClipboard(shareCode);notice="copied";}
    private void shareScope(GraphicsProfile.Scope scope){shareScope=scope;rebuild();copyCode();}
    private void share(){
        int half=(bw-6)/2;
        body(new Action(bx,top+14,half,20,text("scope.look"),()->shareScope(GraphicsProfile.Scope.LOOK),()->shareScope==GraphicsProfile.Scope.LOOK,false));
        body(new Action(bx+half+6,top+14,half,20,text("scope.all"),()->shareScope(GraphicsProfile.Scope.ALL),()->shareScope==GraphicsProfile.Scope.ALL,false));
        GraphicsProfile selected=selected();String name=selected==null?text("defaultName").getString():selected.name();shareCode=ProfileCodec.encode(session.snapshot("export",name,shareScope));
        codeField=field(top+44,text("code"),ProfileCodec.MAX_CODE);codeField.setValue(shareCode);codeField.setEditable(false);
        body(new Action(bx,top+70,bw,20,text("copyCode"),this::copyCode,()->false,true));
    }
    private void importForm(){
        codeField=field(top+16,text("pasteCode"),ProfileCodec.MAX_CODE);codeField.setResponder(value->{pendingImport=null;warning="";});setInitialFocus(codeField);
        body(new Action(bx,top+40,(bw-6)/2,20,text("paste"),()->{codeField.setValue(minecraft.keyboardHandler.getClipboard());parseCode();}));
        body(new Action(bx+(bw+6)/2,top+40,(bw-6)/2,20,text("checkCode"),this::parseCode));
        nameField=field(top+66,text("profileName"),96);
        body(new Action(bx,top+92,bw,20,text("importNew"),()->{
            try{
                if(pendingImport==null){parseCode();if(pendingImport==null)return;}
                GraphicsProfile imported=pendingImport.renamed(nameField.getValue());GraphicsCatalog.merge(session.values(),imported);profiles.put(imported);session.replace(imported);revealSelected=true;back();notice="imported";
            }catch(Exception failure){error(failure);}
        },()->false,true));
    }
    private void parseCode(){try{pendingImport=ProfileCodec.decode(codeField.getValue());GraphicsCatalog.merge(session.values(),pendingImport);nameField.setValue(pendingImport.name());warning=fitWarning(pendingImport,"code.older");notice="code.ready";}catch(Exception failure){pendingImport=null;warning="";error(failure);}}
    // What this version does differently from what the code or profile was saved with; empty when it fits exactly.
    private String fitWarning(GraphicsProfile profile,String olderKey){
        var fit=GraphicsCatalog.fit(profile);List<String> parts=new ArrayList<>();
        if(fit.unchanged()>0)parts.add(text(olderKey,fit.unchanged()).getString());
        if(fit.skipped()>0)parts.add(text("code.newer",fit.skipped()).getString());
        if(fit.adjusted()>0)parts.add(text("code.adjusted",fit.adjusted()).getString());
        return String.join(" ",parts);
    }
    private void color(){
        controls=body(new ControlList(bx,top+40,bw,bottom-top-56));
        hexField=body(new EditBox(font,bx+7,top+18,bw-14,14,text("hex")));hexField.setBordered(false);hexField.setTextShadow(false);hexField.setTextColor(TEXT);hexField.setMaxLength(7);hexField.setValue(hex());
        for(String channel:List.of("R","G","B")){
            var rule=GraphicsCatalog.RULES.get(colorBase+channel);var label=text("channel."+channel.toLowerCase(Locale.ROOT));
            MenuWidgets.Slider slider=new MenuWidgets.Slider(bw-12,rule,label,session,session::checkpoint,()->{if(hexField!=null&&!hexField.isFocused())hexField.setValue(hex());},v->Integer.toString(Math.round(v*255)),this::hold,null);
            controls.add(new ControlRow(slider,text("color.help"),()->true,""));
        }
        hexField.setResponder(value->{String raw=value.startsWith("#")?value.substring(1):value;if(raw.matches("[0-9a-fA-F]{6}")){int rgb=Integer.parseInt(raw,16);for(int i=0;i<3;i++)session.set(colorBase+"RGB".charAt(i),((rgb>>(16-i*8))&255)/255f);}});
    }
    private void number(){
        var rule=GraphicsCatalog.RULES.get(numberKey);nameField=field(top+16,label(rule),32);nameField.setValue(Float.toString(session.value(numberKey)));setInitialFocus(nameField);
        body(new Action(bx,top+56,bw,20,text("apply"),()->{
            try{float v=Float.parseFloat(nameField.getValue().strip().replace(',','.'));v=rule.normalize(v);session.checkpoint();session.set(numberKey,v);back();}catch(Exception failure){notice="number.invalid";}
        },()->false,true));
    }
    // Leaving with unsaved changes: save them, discard them, or keep editing.
    private void confirm(){
        int third=(Math.min(bw,420)-12)/3;
        body(new Action(bx,top+40,third,20,text("confirm.save"),this::finishAndClose,()->false,true));
        body(new Action(bx+third+6,top+40,third,20,text("confirm.discard"),()->{session.abandon();closed=true;minecraft.gui.setScreen(parent);}));
        body(new Action(bx+2*(third+6),top+40,third,20,text("confirm.back"),this::back));
    }
    private void benchmark(){
        controls=body(new ControlList(bx,top+14,bw,bottom-top-24));
        controls.add(heading(text("benchmark.allMacs")));
        controls.add(heading(text("benchmark.display")));
        controls.add(new ControlRow(new Action(0,0,bw-12,20,text("benchmark.start",siliconshaders.metal.bench.BenchPlan.profile("diagnose",false).minutes()),()->startBenchmark("diagnose")),text("benchmark.help"),()->true,""));
        controls.add(new ControlRow(new Action(0,0,bw-12,20,text("benchmark.quick",siliconshaders.metal.bench.BenchPlan.profile("kurz",false).minutes()),()->startBenchmark("kurz")),text("benchmark.quickHelp"),()->true,""));
        controls.add(heading(text("benchmark.leave")));
        controls.add(heading(text("benchmark.report")));
        controls.add(new ControlRow(new Action(0,0,bw-12,18,text("benchmark.folder"),()->{try{java.nio.file.Path folder=runtime.directory().resolve("siliconshaders-benchmarks");java.nio.file.Files.createDirectories(folder);new ProcessBuilder("open",folder.toString()).start();}catch(Exception failure){error(failure);}}),Component.empty(),()->true,""));
    }
    private void startBenchmark(String profile){
        try{session.finish(profiles,copyName());closed=true;siliconshaders.metal.bench.ShaderBench.start(profile);}catch(Exception failure){error(failure);}
    }
    private void finishAndClose(){try{session.finish(profiles,copyName());closed=true;minecraft.gui.setScreen(parent);}catch(Exception failure){back();error(failure);}}
    private void finish(){if(page!=Page.MAIN){back();return;}finishAndClose();}
    private void support(){
        // Open from this settings button only. Keeping this Screen avoids abandoning the live settings draft in removed().
        try{com.mojang.blaze3d.Blaze3D.openUri(SUPPORT_URL);}
        catch(RuntimeException failure){notice="support.failed";siliconshaders.metal.Log.warn("[SiliconShaders] Could not open Ko-fi: "+failure.getMessage());}
    }
    private void error(Exception failure){String key=failure.getMessage();notice=key!=null&&(key.startsWith("profile.")||key.startsWith("code.")||key.startsWith("profiles."))?key:"saveFailed";}
    private String hex(){return String.format(Locale.ROOT,"#%02X%02X%02X",Math.round(session.value(colorBase+"R")*255),Math.round(session.value(colorBase+"G")*255),Math.round(session.value(colorBase+"B")*255));}

    // ---- input ----
    private void hold(MenuWidgets.Slider slider){heldSlider=minecraft.level!=null?slider:null;}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){if(heldSlider!=null)return heldSlider.mouseDragged(event,dx,dy);return super.mouseDragged(event,dx,dy);}
    @Override public boolean mouseReleased(MouseButtonEvent event){
        if(heldSlider!=null){MenuWidgets.Slider slider=heldSlider;slider.mouseReleased(event);setDragging(false);if(controls!=null)controls.setDragging(false);return true;}return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(heldSlider!=null)return true;return super.mouseScrolled(x,y,horizontal,vertical);}
    private void cancelSliderDrag(){if(heldSlider!=null)heldSlider.endHold();setDragging(false);if(controls!=null)controls.setDragging(false);}
    @Override public void tick(){
        super.tick();if(hexField!=null&&!hexField.isFocused()&&!hexField.getValue().equals(hex()))hexField.setValue(hex());
        if(undoButton!=null)undoButton.active=session.canUndo();
        // GUI release events end a normal drag; a release outside the window may never arrive.
        if(heldSlider!=null&&!minecraft.isWindowActive())cancelSliderDrag();
    }
    @Override public void onClose(){
        if(heldSlider!=null){cancelSliderDrag();return;}
        if(preview){leavePreview();return;}
        if(page!=Page.MAIN){back();return;}
        if(editing&&!query.isBlank()){query="";if(search!=null)search.setValue("");rebuild();return;}
        if(session.changed()){open(Page.CONFIRM);return;}
        session.abandon();closed=true;minecraft.gui.setScreen(parent);
    }
    @Override public void removed(){heldSlider=null;lastFeature=feature;if(!closed)session.abandon();}
    @Override public boolean keyPressed(KeyEvent event){if(heldSlider!=null){if(event.isEscape())cancelSliderDrag();return true;}if(preview){if(event.key()==32)toggleComparison();else leavePreview();return true;}return super.keyPressed(event);}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){if(preview){leavePreview();return true;}return super.mouseClicked(event,twice);}

    // ---- drawing ----
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float tick){
        // Exactly one vanilla blur request. A held slider draws over the clear world.
        if(!preview&&heldSlider==null){if(minecraft.level==null)extractPanorama(g,tick);extractBlurredBackground(g);}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
        if(heldSlider!=null){round(g,heldSlider.getX()-8,heldSlider.getY()-6,heldSlider.getWidth()+16,heldSlider.getHeight()+12,0xb3161b20);heldSlider.extractRenderState(g,mx,my,tick);return;}
        if(preview){g.centeredText(font,text(comparing?"preview.standard":"preview.yours"),width/2,height-34,ACCENT);g.centeredText(font,text("preview.close"),width/2,height-22,TEXT);return;}
        for(Runnable refresh:refreshers)refresh.run();
        g.fill(0,0,width,height,BG);g.horizontalLine(12,width-12,58,LINE);
        if(BrandIcon.ready())g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,BrandIcon.ID,10,3,0f,0f,28,28,BrandIcon.SIZE,BrandIcon.SIZE,BrandIcon.SIZE,BrandIcon.SIZE);
        g.text(font,"SiliconShaders",44,13,TEXT,false);
        if(search!=null)round(g,search.getX()-5,34,search.getWidth()+10,18,0xad222a32);
        String heading=switch(page){
            case MAIN->editing?(!query.isBlank()||changedOnly?text("results").getString():featureTitle(feature).getString()):text("packs").getString();
            case MANAGE->text("manage").getString();case NAME->text(editId.isEmpty()?"newProfile":"rename").getString();case SHARE->text("share").getString();
            case IMPORT->text("import").getString();case COLOR->colorLabel(colorBase).getString();case NUMBER->label(GraphicsCatalog.RULES.get(numberKey)).getString();case CONFIRM->text("confirm.title").getString();case BENCHMARK->text("benchmark").getString();
        };
        g.text(font,fit(heading,bw),bx,top,TEXT,false);
        GraphicsProfile selected=selected();String name=(selected==null?text("current").getString():selected.name())+(selected!=null&&!session.matches(selected)?" *":"");
        if(width>484)g.text(font,fit(text("active",name).getString(),width-454),276,height-19,MUTED,false);
        if(page==Page.MAIN&&!editing){
            List<GraphicsProfile> packs=new ArrayList<>(presets);packs.addAll(profiles.profiles());
            int q=top+12+packRows(packs.size())*36+18;
            g.text(font,text("quality").getString(),bx,q-11,MUTED,false);
            if(qualityLevel()<0)g.text(font,text("quality.custom").getString(),bx+LEVELS.length*Math.min(90,(bw-6*(LEVELS.length-1))/LEVELS.length)+6*LEVELS.length,q+6,MUTED,false);
        }
        if((page==Page.NAME||page==Page.NUMBER)&&nameField!=null)round(g,bx,top+13,bw,23,SURFACE);
        if(page==Page.SHARE&&codeField!=null)round(g,bx,top+41,bw,23,SURFACE);
        if(page==Page.IMPORT){
            round(g,bx,top+13,bw,23,SURFACE);round(g,bx,top+63,bw,23,SURFACE);
            List<String> lines=wrap(warning.isEmpty()?text("import.help").getString():warning,bw,3);
            for(int i=0;i<lines.size();i++)g.text(font,lines.get(i),bx,top+118+i*11,warning.isEmpty()?MUTED:WARNING,false);
        }
        if(page==Page.COLOR)round(g,bx,top+14,bw,20,SURFACE);
        if(page==Page.NUMBER){var rule=GraphicsCatalog.RULES.get(numberKey);g.text(font,text("number.range",value(rule,rule.min()),value(rule,rule.max())),bx,top+42,MUTED,false);}
        if(page==Page.CONFIRM){List<String> lines=wrap(text("confirm.text").getString(),bw,2);for(int i=0;i<lines.size();i++)g.text(font,lines.get(i),bx,top+14+i*11,MUTED,false);}
        info="";
        super.extractRenderState(g,mx,my,tick);
        // The description of the hovered setting, in one place instead of under every row.
        boolean warn=notice.isEmpty()&&info.isEmpty()&&page==Page.MAIN&&!warning.isEmpty();
        String line=!notice.isEmpty()?text(notice).getString():warn?warning:info;
        boolean failed=!notice.isEmpty()&&(notice.equals("saveFailed")||notice.equals("support.failed")||notice.startsWith("code.")&&!notice.equals("code.ready"));
        if(!line.isEmpty())g.text(font,fit(line,bw),bx,bottom-10,failed?ERROR:warn?WARNING:MUTED,false);
    }

    // ---- lists ----
    // Rows stop short of the right edge and the scrollbar sits inside the list's own bounds. Placed beside the rows
    // (vanilla), it fell outside the list: drawn, but its clicks never reached the list, so it could not be dragged.
    private final class ControlList extends ContainerObjectSelectionList<ControlRow> {
        ControlList(int x,int y,int w,int h){super(GraphicsScreen.this.minecraft,w,h,y,24);setX(x);centerListVertically=false;}
        void add(ControlRow row){addEntry(row);}
        @Override public int getRowWidth(){return getWidth()-12;}
        @Override public int getRowLeft(){return getX();}
        @Override protected int scrollBarX(){return getRight()-scrollbarWidth();}
        @Override protected void extractListBackground(GuiGraphicsExtractor g){}
        @Override protected void extractListSeparators(GuiGraphicsExtractor g){}
    }
    private final class ControlRow extends ContainerObjectSelectionList.Entry<ControlRow> {
        private final AbstractWidget widget;private final Component help;private final BooleanSupplier enabled;private final String color,key;private final boolean heading;private final Icon reset;
        ControlRow(AbstractWidget widget,Component help,BooleanSupplier enabled,String color){this(widget,help,enabled,color,false);}
        ControlRow(AbstractWidget widget,Component help,BooleanSupplier enabled,String color,boolean heading){this(widget,help,enabled,color,heading,"");}
        ControlRow(AbstractWidget widget,Component help,BooleanSupplier enabled,String color,boolean heading,String key){
            this.widget=widget;this.help=help;this.enabled=enabled;this.color=color;this.heading=heading;this.key=key;
            reset=key.isEmpty()?null:new Icon(Component.literal("\u21ba"),()->resetRow(key));
            if(reset!=null)reset.setTooltip(Tooltip.create(text("resetRow")));
        }
        @Override public List<? extends GuiEventListener> children(){return widget==null?List.of():reset==null?List.of(widget):List.of(widget,reset);}
        @Override public List<? extends NarratableEntry> narratables(){return widget==null?List.of():List.of(widget);}
        @Override public void extractContent(GuiGraphicsExtractor g,int mx,int my,boolean hover,float tick){
            int x=getContentX(),y=getContentY(),w=getContentWidth();
            if(heading){g.text(font,fit(help.getString(),w-2),x+1,y+getContentHeight()-11,ACCENT,false);g.horizontalLine(x+1,x+w-1,y+getContentHeight()-1,LINE);return;}
            if(widget==null){g.text(font,fit(help.getString(),w-2),x+1,y+6,MUTED,false);return;}
            // A setting's row keeps room on the right, so the reset symbol appearing never moves the slider under the mouse.
            int inner=reset==null?w:w-24;
            widget.setRectangle(inner,widget.getHeight(),x,y);widget.active=enabled.getAsBoolean();widget.extractRenderState(g,mx,my,tick);
            if(!color.isEmpty()){int rgb=0;for(char c:"RGB".toCharArray())rgb=(rgb<<8)|Math.round(session.value(color+c)*255);round(g,x+inner-24,y+4,16,10,0xff000000|rgb);}
            if(reset!=null) {
                int level=GraphicsCatalog.cost(key);boolean saving=GraphicsCatalog.saves(key);
                // One or two dots for what it costs; a green one for the setting that saves.
                for(int i=0;i<level;i++)g.fill(x+inner+2,y+5+i*5,x+inner+5,y+8+i*5,0xffe8c37a);
                if(saving)g.fill(x+inner+2,y+5,x+inner+5,y+8,ACCENT);
                reset.visible=changed(key);reset.setRectangle(16,16,x+w-16,y+1);reset.extractRenderState(g,mx,my,tick);
            }
            if(hover)info=hoverText(key,help);
        }
    }
    private final class FeatureList extends ContainerObjectSelectionList<FeatureRow> {
        FeatureList(int x,int y,int w,int h){super(GraphicsScreen.this.minecraft,w,h,y,20);setX(x);centerListVertically=false;}
        void add(FeatureRow row){addEntry(row);}
        @Override public int getRowWidth(){return getWidth()-8;}
        @Override public int getRowLeft(){return getX();}
        @Override protected int scrollBarX(){return getRight()-scrollbarWidth();}
        @Override protected void extractListBackground(GuiGraphicsExtractor g){}
        @Override protected void extractListSeparators(GuiGraphicsExtractor g){}
    }
    // A feature: its name, and a dot when it can be switched on or off (bright: on). Or a heading over the features below it.
    private final class FeatureRow extends ContainerObjectSelectionList.Entry<FeatureRow> {
        private final GraphicsCatalog.Feature f;private final Action action;private final Component title;
        FeatureRow(GraphicsCatalog.Feature f){this.f=f;title=null;action=new Action(0,0,100,18,featureTitle(f.id()),()->{feature=f.id();query="";changedOnly=false;if(search!=null)search.setValue("");rebuild();},()->query.isBlank()&&!changedOnly&&feature.equals(f.id()),false);}
        FeatureRow(Component title){f=null;action=null;this.title=title;}
        @Override public List<? extends GuiEventListener> children(){return action==null?List.of():List.of(action);}
        @Override public List<? extends NarratableEntry> narratables(){return action==null?List.of():List.of(action);}
        @Override public void extractContent(GuiGraphicsExtractor g,int mx,int my,boolean hover,float tick){
            if(action==null){g.text(font,fit(title.getString(),getContentWidth()-2),getContentX()+1,getContentY()+getContentHeight()-10,ACCENT,false);return;}
            // How many settings differ from the standard here: in the name, where it can never overlap anything.
            int changes=changedIn(f.id());action.setMessage(changes>0?featureTitle(f.id()).copy().append(" ("+changes+")"):featureTitle(f.id()));
            action.setRectangle(getContentWidth(),18,getContentX(),getContentY());action.extractRenderState(g,mx,my,tick);
            if(f.toggle()!=null)round(g,getContentX()+getContentWidth()-9,getContentY()+7,4,4,featureOn(f)?ACCENT:0xff55616a);
        }
    }
}
