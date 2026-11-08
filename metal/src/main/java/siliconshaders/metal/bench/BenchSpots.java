package siliconshaders.metal.bench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * The camera spots of the benchmark world, searched once on the server thread from the world spawn (the same seed gives
 * the same terrain, so every Mac gets the same spots) and kept in the world folder. Positions are the player's feet.
 */
final class BenchSpots {
    /** Bump when the search changes, so old worlds search again. */
    static final int VERSION=3;
    static final int RADIUS=448,STEP=16,WATER_RADIUS=160,BIOME_RADIUS=4000;
    private static final Gson JSON=new Gson();
    private BenchSpots() {}

    static Map<String,double[]> load(Path file) {
        try {
            if(!Files.isRegularFile(file))return null;
            JsonObject o=JSON.fromJson(Files.readString(file),JsonObject.class);
            if(o==null||!o.has("version")||o.get("version").getAsInt()!=VERSION)return null;
            Map<String,double[]> spots=new LinkedHashMap<>();
            for(var e:o.getAsJsonObject("spots").entrySet())spots.put(e.getKey(),JSON.fromJson(e.getValue(),double[].class));
            return spots;
        } catch(Exception failure) { return null; }
    }
    static void save(Path file,Map<String,double[]> spots) throws java.io.IOException {
        JsonObject o=new JsonObject();o.addProperty("version",VERSION);o.add("spots",JSON.toJsonTree(spots));
        Files.createDirectories(file.getParent());Files.writeString(file,JSON.toJson(o)+"\n");
    }

    /** Server thread only (generates the chunks it looks at). A spot that is not found is left out. */
    static Map<String,double[]> find(ServerLevel level) {
        Map<String,double[]> spots=new LinkedHashMap<>();
        BlockPos spawn=level.getRespawnData().pos();
        int ground=Math.max(height(level,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,spawn.getX(),spawn.getZ()),level.getSeaLevel());
        // Above the spawn, looking over the land.
        spots.put("land",new double[]{spawn.getX()+0.5,ground+12,spawn.getZ()+0.5});
        int[] forest=spiral(spawn,RADIUS,(x,z)->forest(level,x,z));
        if(forest!=null)spots.put("wald",new double[]{forest[0]+0.5,height(level,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,forest[0],forest[1]),forest[1]+0.5});
        // Water near the spawn, else around the nearest ocean, then river (the biome search generates no chunks).
        int[] water=spiral(spawn,WATER_RADIUS,(x,z)->water(level,x,z));
        for(TagKey<Biome> kind:java.util.List.of(BiomeTags.IS_OCEAN,BiomeTags.IS_RIVER)) {
            if(water!=null)break;
            var found=level.findClosestBiome3d(biome->biome.is(kind),new BlockPos(spawn.getX(),level.getSeaLevel(),spawn.getZ()),BIOME_RADIUS,32,64);
            if(found!=null)water=spiral(found.getFirst(),WATER_RADIUS,(x,z)->water(level,x,z));
        }
        if(water!=null) {
            int surface=height(level,Heightmap.Types.WORLD_SURFACE,water[0],water[1]);
            spots.put("wasser",new double[]{water[0]+0.5,surface+1.5,water[1]+0.5});
            spots.put("unterwasser",new double[]{water[0]+0.5,surface-3,water[1]+0.5});
        }
        return spots;
    }
    /** Level.getHeight answers the world bottom for a chunk that is not loaded: load (generate) it first. */
    private static int height(ServerLevel level,Heightmap.Types type,int x,int z) {
        level.getChunk(x>>4,z>>4);
        return level.getHeight(type,x,z);
    }
    interface Test { boolean at(int x,int z); }
    /** Square rings around the spawn, every STEP blocks, in a fixed order; the first column that passes. */
    static int[] spiral(BlockPos center,int radius,Test test) {
        for(int r=0;r<=radius;r+=STEP)
            for(int dx=-r;dx<=r;dx+=STEP)
                for(int dz=-r;dz<=r;dz+=STEP) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
                    if(test.at(center.getX()+dx,center.getZ()+dz))return new int[]{center.getX()+dx,center.getZ()+dz};
                }
        return null;
    }
    /** A dense canopy at least four blocks over dry ground, here and four blocks to each side. */
    private static boolean forest(ServerLevel level,int x,int z) {
        int[][] around={{0,0},{4,0},{-4,0},{0,4},{0,-4}};
        for(int[] d:around) {
            int top=height(level,Heightmap.Types.WORLD_SURFACE,x+d[0],z+d[1])-1;
            if(!(level.getBlockState(new BlockPos(x+d[0],top,z+d[1])).getBlock() instanceof LeavesBlock))return false;
        }
        int ground=height(level,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
        int top=height(level,Heightmap.Types.WORLD_SURFACE,x,z)-1;
        return top-ground>=4&&level.getFluidState(new BlockPos(x,ground-1,z)).isEmpty();
    }
    /** Open water at least five deep, with water at the same surface six blocks to each side. */
    private static boolean water(ServerLevel level,int x,int z) {
        int surface=height(level,Heightmap.Types.WORLD_SURFACE,x,z)-1;
        int[][] around={{0,0},{6,0},{-6,0},{0,6},{0,-6}};
        for(int[] d:around) {
            if(height(level,Heightmap.Types.WORLD_SURFACE,x+d[0],z+d[1])-1!=surface)return false;
            if(!level.getFluidState(new BlockPos(x+d[0],surface,z+d[1])).isSourceOfType(Fluids.WATER))return false;
        }
        return surface+1-height(level,Heightmap.Types.OCEAN_FLOOR,x,z)>=5;
    }
}
