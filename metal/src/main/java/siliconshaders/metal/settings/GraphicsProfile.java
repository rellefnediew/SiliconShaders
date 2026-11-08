package siliconshaders.metal.settings;

import java.util.*;

/** Portable stable names, independent of shader-packet indices and hardware implementation choices. */
public record GraphicsProfile(String id,String name,Scope scope,Map<String,Float> values) {
    public enum Scope { LOOK, ALL }
    public GraphicsProfile {
        Objects.requireNonNull(id);Objects.requireNonNull(scope);Objects.requireNonNull(values);
        name=cleanName(name);
        if(values.isEmpty()||values.size()>2048)throw new IllegalArgumentException("profile.data");
        TreeMap<String,Float> copy=new TreeMap<>();
        values.forEach((key,value)->{
            if(key==null||!key.matches("[a-zA-Z][a-zA-Z0-9_.]{0,79}")||value==null||!Float.isFinite(value))throw new IllegalArgumentException("profile.data");
            copy.put(key,value);
        });values=Collections.unmodifiableMap(copy);
    }
    public static String cleanName(String text){
        if(text==null)throw new IllegalArgumentException("profile.name");
        String result=text.strip();
        if(result.isEmpty()||result.codePointCount(0,result.length())>48||result.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))throw new IllegalArgumentException("profile.name");
        return result;
    }
    public GraphicsProfile renamed(String name){return new GraphicsProfile(id,name,scope,values);}
}
