package siliconshaders.metal.own;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Immutable, registry-validated CPU classification. Workers carry the actual emitting block's ID into each quad. */
public final class SurfaceMaterials {
    public static final boolean ON=Boolean.parseBoolean(System.getProperty("siliconshaders.sun.materials","true"));
    public static final int RECORD_FLAG=1<<19;
    public record Rule(int profile,String domain,String selector){}
    public record Catalog(List<String> profiles,List<Rule> rules){}
    public record Coverage(int matchedStates,int matchedBlocks,int ignoredNames,int ignoredSelectors){}
    private record Lookup(byte[] ids,byte[] transparent,Coverage coverage){}
    private static final class Holder {static final Lookup LOOKUP=build();}
    private static final class CatalogHolder {static final Catalog CATALOG=read();}
    private SurfaceMaterials(){}
    public static Catalog catalog(){return CatalogHolder.CATALOG;}
    public static int profile(String name){return catalog().profiles().indexOf(name);}
    public static int id(BlockState state){
        if(!ON||state.isAir())return 0;
        int id=Block.BLOCK_STATE_REGISTRY.getId(state);byte[] values=Holder.LOOKUP.ids();
        return id>=0&&id<values.length?values[id]&255:0;
    }
    /** 1 clear glass, 2 stained glass, 3 tinted glass, 4 ice, 5 slime, 6 honey; zero preserves the old path. */
    public static int translucentId(BlockState state){
        if(!ON||state.isAir())return 0;
        int id=Block.BLOCK_STATE_REGISTRY.getId(state);byte[] values=Holder.LOOKUP.transparent();
        return id>=0&&id<values.length?values[id]&255:0;
    }
    public static Coverage coverage(){return Holder.LOOKUP.coverage();}
    private static Catalog read(){
        try(var stream=SurfaceMaterials.class.getResourceAsStream("/siliconshaders/materials/blocks.json")){
            if(stream==null)throw new IllegalStateException("Missing material catalog");
            var json=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> names=new ArrayList<>();for(var item:json.getAsJsonArray("profiles")){
                var p=item.getAsJsonObject();if(p.get("id").getAsInt()!=names.size())throw new IllegalArgumentException("Material profile order");names.add(p.get("name").getAsString());
            }
            if(names.size()>256||!names.getFirst().equals("default"))throw new IllegalArgumentException("Material palette size");
            List<Rule> rules=new ArrayList<>();for(var item:json.getAsJsonArray("rules")){
                var r=item.getAsJsonObject();int p=r.get("profile").getAsInt();if(p<0||p>=names.size())throw new IllegalArgumentException("Material profile ID");
                for(var selector:r.getAsJsonArray("selectors"))rules.add(new Rule(p,r.get("domain").getAsString(),selector.getAsString()));
            }
            return new Catalog(List.copyOf(names),List.copyOf(rules));
        }catch(Exception e){throw new IllegalStateException("Cannot read surface materials",e);}
    }
    private static Lookup build(){
        byte[] values=new byte[Block.BLOCK_STATE_REGISTRY.size()];int ignoredNames=0,ignoredSelectors=0;Set<Block> blocks=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var rule:catalog().rules()){
            String[] parts=rule.selector().split(":");int first=parts.length>1&&!parts[1].contains("=")?2:1;
            String name=first==2?parts[0]+":"+parts[1]:"minecraft:"+parts[0];
            var opt=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(name));if(opt.isEmpty()){ignoredNames++;continue;}
            Block block=opt.get();boolean any=false;
            for(BlockState state:block.getStateDefinition().getPossibleStates())if(matches(state,parts,first)){
                int id=Block.BLOCK_STATE_REGISTRY.getId(state);if(id>=0&&id<values.length){values[id]=(byte)rule.profile();any=true;}
            }
            if(any)blocks.add(block);else ignoredSelectors++;
        }
        int matched=0;for(byte id:values)if(id!=0)matched++;
        Coverage coverage=new Coverage(matched,blocks.size(),ignoredNames,ignoredSelectors);
        siliconshaders.metal.Log.infof("[SiliconShaders materials] %d profiles, %d blocks, %d non-neutral states; %d absent names and %d incompatible selectors skipped%n",catalog().profiles().size(),coverage.matchedBlocks(),matched,ignoredNames,ignoredSelectors);
        byte[] transparent=new byte[values.length];
        for(BlockState state:Block.BLOCK_STATE_REGISTRY){
            int at=Block.BLOCK_STATE_REGISTRY.getId(state),profile=values[at]&255;String name=catalog().profiles().get(profile);
            if(name.equals("ice"))transparent[at]=4;
            else if(name.equals("slime"))transparent[at]=5;
            else if(name.equals("honey"))transparent[at]=6;
            else if(name.equals("glass")){
                String path=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                transparent[at]=(byte)(path.equals("tinted_glass")?3:path.contains("stained_glass")?2:1);
            }
        }
        return new Lookup(values,transparent,coverage);
    }
    private static boolean matches(BlockState state,String[] parts,int first){
        for(int i=first;i<parts.length;i++){
            String[] pair=parts[i].split("=",2);if(pair.length!=2)return false;
            Property<?> property=state.getBlock().getStateDefinition().getProperty(pair[0]);
            if(property==null||!propertyValue(state,property).equals(pair[1]))return false;
        }return true;
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
}
