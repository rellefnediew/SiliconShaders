package siliconshaders.metal.settings;

import java.util.*;

/** Named presets are immutable checkpoints. A working copy and action-level undo are separate from profile storage. */
public final class MenuSession {
    private record Undo(Map<String,Float> values,String selected){}
    private final GraphicsSettings runtime;private final Map<String,Float> opening;
    private Map<String,Float> draft;private String selected;private final String openingSelected;private final Deque<Undo> history=new ArrayDeque<>();private boolean committed;
    public MenuSession(GraphicsSettings runtime,String selected){this.runtime=runtime;this.opening=Map.copyOf(runtime.capture());this.draft=new LinkedHashMap<>(opening);this.selected=selected==null?"":selected;this.openingSelected=this.selected;}
    public Map<String,Float> values(){return Collections.unmodifiableMap(draft);}
    public float value(String key){return draft.getOrDefault(key,GraphicsCatalog.RULES.get(key).neutral());}
    public String selected(){return selected;}
    public void select(String id){selected=id==null?"":id;}
    public void checkpoint(){if(history.size()>=32)history.removeFirst();history.addLast(new Undo(Map.copyOf(draft),selected));}
    public boolean canUndo(){return !history.isEmpty();}
    public void set(String key,float value){var rule=GraphicsCatalog.RULES.get(key);if(rule==null)return;draft.put(key,rule.normalize(value));runtime.preview(draft);}
    public void replace(GraphicsProfile profile){Map<String,Float> next=GraphicsCatalog.merge(draft,profile);checkpoint();draft=next;selected=profile.id();runtime.preview(draft);}
    /** Several settings at once as one undo step, each clamped to its range (feature reset, quality level). */
    public void setAll(Map<String,Float> values){checkpoint();for(var e:values.entrySet()){var r=GraphicsCatalog.RULES.get(e.getKey());if(r!=null)draft.put(r.key(),r.normalize(Math.clamp(e.getValue(),r.min(),r.max())));}runtime.preview(draft);}
    /** Anything differs from what the menu opened with: values or the selected profile. */
    public boolean changed(){return !draft.equals(opening)||!selected.equals(openingSelected);}
    public void undo(){Undo previous=history.pollLast();if(previous!=null){draft=new LinkedHashMap<>(previous.values());selected=previous.selected();runtime.preview(draft);}}
    public boolean matches(GraphicsProfile profile){if(profile==null)return false;for(var e:GraphicsCatalog.portable(profile.values(),profile.scope()).entrySet())if(Math.abs(value(e.getKey())-e.getValue())>.0001f)return false;return true;}
    public void finish(ProfileStore store) throws java.io.IOException {runtime.commit(draft,store.activationDocument(selected));store.activationPublished(selected);committed=true;}
    /** Shows other values on screen without touching the working copy: a before and after comparison. */
    public void show(Map<String,Float> values){runtime.preview(values);}
    public void showDraft(){runtime.preview(draft);}
    public void abandon(){if(!committed)runtime.preview(opening);}
    public GraphicsProfile snapshot(String id,String name,GraphicsProfile.Scope scope){return new GraphicsProfile(id,name,scope,GraphicsCatalog.portable(draft,scope));}
}
