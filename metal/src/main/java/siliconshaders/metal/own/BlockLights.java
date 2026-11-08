package siliconshaders.metal.own;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lwjgl.system.MemoryUtil;

/** World-space coefficients, independent of mesh/frustum/shadow visibility. GPU owns the RGB solution. */
public final class BlockLights {
    public static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("siliconshaders.sun.coloredBlocklight","true"));
    public static final int SWITCH=siliconshaders.metal.sun.SunTuning.index("coloredLight"),RANGE=siliconshaders.metal.sun.SunTuning.index("coloredLightRange");
    // Client world and light-engine callbacks only queue what they touched; update() drains the queues once per frame.
    // No callback triggers a scan of the whole render distance.
    private static LongOpenHashSet BLOCKS=new LongOpenHashSet(),LIGHT=new LongOpenHashSet(),UNLOADED=new LongOpenHashSet();
    private static final LongOpenHashSet NOTHING=new LongOpenHashSet();
    /** A block in this section changed: its coefficients are re-read. */
    public static synchronized void changed(BlockPos at){BLOCKS.add(SectionPos.asLong(at));}
    /** Vanilla block light in this section changed: only whether the section joins the field is re-checked. */
    public static synchronized void lightChanged(long section){LIGHT.add(section);}
    public static synchronized void unloaded(int chunkX,int chunkZ){UNLOADED.add(chunk(chunkX,chunkZ));}
    private static long chunk(int x,int z){return (long)x<<32|(z&0xffffffffL);}
    private static final class Palette {
        static final int[] CELLS=make();
        private static int[] make(){
            int[] a=new int[Block.BLOCK_STATE_REGISTRY.size()];
            for(BlockState s:Block.BLOCK_STATE_REGISTRY){
                String name=BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                int level=s.getLightEmission(),flags=0;float r=1,g=.58f,b=.25f;
                // Lava (still and flowing) and spreading fire keep vanilla's block light colour and count as air in the field:
                // flowing lava and fire change every few ticks, and nothing they do may trigger a recalculation.
                // Campfires stay colored.
                if(name.equals("lava")||name.equals("fire")||name.equals("soul_fire"))level=0;
                boolean extra=false;
                if(name.contains("soul")){r=.30f;g=.91f;b=1;}
                else if(name.contains("redstone")){r=1;g=.10f;b=.10f;extra=name.equals("redstone_block");}
                else if(name.contains("amethyst")){r=.72f;g=.35f;b=1;}
                else if(name.equals("glow_lichen")||name.equals("sea_pickle")){r=.65f;g=1;b=1;}
                else if(name.equals("calibrated_sculk_sensor")){r=.72f;g=.35f;b=1;}
                else if(name.contains("sculk")){r=.18f;g=.72f;b=1;}
                else if(name.equals("sea_lantern")||name.equals("end_rod")){r=.72f;g=.87f;b=1;}
                else if(name.equals("beacon")){r=.35f;g=.75f;b=1;}
                else if(name.equals("verdant_froglight")){r=.45f;g=1;b=.45f;}
                else if(name.equals("pearlescent_froglight")){r=1;g=.45f;b=.82f;}
                else if(name.equals("ochre_froglight")){r=1;g=.78f;b=.32f;}
                else if(name.contains("portal")||name.equals("crying_obsidian")){r=.82f;g=.18f;b=1;}
                else if(name.startsWith("copper_")&&(name.endsWith("torch")||name.endsWith("lantern"))){r=.65f;g=1;b=.78f;}
                else if(name.equals("lava")||name.equals("magma_block")){r=1;g=.28f;b=.06f;}
                boolean glass=name.contains("stained_glass");
                if(glass){
                    float[] tint=dye(name);r=tint[0];g=tint[1];b=tint[2];flags=1<<25;
                }else if(level>0||extra){
                    float strength=extra?.25f:level/15f;r*=strength;g*=strength;b*=strength;
                    if(extra&&level==0)flags|=1<<24;
                }else{r=g=b=0;}
                if(!glass&&level==0&&!extra){
                    if(s.useShapeForLightOcclusion()){
                        Direction[] dirs={Direction.EAST,Direction.WEST,Direction.UP,Direction.DOWN,Direction.SOUTH,Direction.NORTH};
                        for(int face=0;face<6;face++)if(Block.isFaceFull(s.getFaceOcclusionShape(dirs[face]),dirs[face]))flags|=1<<(26+face);
                    }else if(s.getLightDampening()>=15)flags|=63<<26;
                }
                a[Block.BLOCK_STATE_REGISTRY.getId(s)]=flags|Math.round(r*255)|Math.round(g*255)<<8|Math.round(b*255)<<16;
            }
            return a;
        }
        private static float[] dye(String name){
            String[] colors={"light_blue","light_gray","orange","magenta","yellow","lime","pink","gray","cyan","purple","blue","brown","green","red","black","white"};
            float[][] rgb={{.5f,.65f,1},{1,1,1},{1,.3f,.1f},{1,.1f,1},{1,1,.1f},{.1f,1,.1f},{1,.4f,1},{1,1,1},{.3f,.8f,1},{.7f,.3f,1},{.1f,.15f,1},{1,.75f,.5f},{.3f,1,.3f},{1,.1f,.1f},{1,1,1},{1,1,1}};
            for(int i=0;i<colors.length;i++)if(name.startsWith(colors[i]+"_"))return rgb[i];return new float[]{1,1,1};
        }
    }
    /** cells: the coefficient grid while the section is in the field (null otherwise); stale: re-read on next use. */
    private record Snapshot(LevelChunkSection section,int[] cells,boolean source,boolean lit,boolean extra,boolean stale){
        Snapshot lit(boolean value){return new Snapshot(section,cells,source,value,extra,stale);}
    }
    private final Long2ObjectOpenHashMap<Snapshot> cache=new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<long[]> resident=new Long2ObjectOpenHashMap<>(); // chunk key -> its section keys
    private ClientLevel level;private int cx=Integer.MIN_VALUE,cy,cz,radius,range;private LongOpenHashSet previous=new LongOpenHashSet();
    private long lastOwner;
    private Long2IntOpenHashMap slots=new Long2IntOpenHashMap();private int generation,uploadedFade=-1;
    private static final int DELTA=0x424c4431; // BLD1: stable layout, only changed coefficient sections
    private static final int RING=1;
    private ByteBuffer packet; // reused between uploads; native code copies it during the upload call
    private static boolean lit(net.minecraft.world.level.lighting.LayerLightEventListener light,long key){
        var data=light.getDataLayerData(SectionPos.of(key));return data!=null&&!data.isEmpty();
    }
    private static boolean source(LevelChunkSection section){
        return section.maybeHas(s->{int cell=Palette.CELLS[Block.BLOCK_STATE_REGISTRY.getId(s)];return (cell&0xffffff)!=0&&(cell&(1<<25))==0;});
    }
    // Snapshot a chunk's sections; false while it is not loaded.
    private boolean load(ClientLevel world,int x,int z,net.minecraft.world.level.lighting.LayerLightEventListener light){
        LevelChunk chunk=world.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);if(chunk==null)return false;
        var all=chunk.getSections();long[] keys=new long[all.length];
        for(int sy=0;sy<all.length;sy++){
            long key=SectionPos.asLong(x,world.getSectionYFromSectionIndex(sy),z);keys[sy]=key;
            Snapshot old=cache.get(key);
            cache.put(key,new Snapshot(all[sy],old==null?null:old.cells(),source(all[sy]),lit(light,key),old!=null&&old.extra(),true));
        }
        resident.put(chunk(x,z),keys);return true;
    }
    private void drop(long chunk){long[] keys=resident.remove(chunk);if(keys!=null)for(long key:keys)cache.remove(key);}
    private boolean inRange(int x,int z){return Math.abs(x-cx)<=radius&&Math.abs(z-cz)<=radius;}
    /**
     * The field covers a cube of chunks and sections around the camera's section, range on each side (never past the
     * render distance). Colored light fades into the plain block light colour over the last part before (range-1)*16
     * blocks, where missing neighbours outside the cube would start to show.
     */
    public void update(long own,double x,double y,double z,boolean enabled,int requestedRange){
        Minecraft mc=Minecraft.getInstance();ClientLevel next=mc.level;
        if(!enabled||!ENABLED||next==null){OwnNative.blockLightUpload(own,0,0);cache.clear();resident.clear();level=null;previous=new LongOpenHashSet();slots=new Long2IntOpenHashMap();uploadedFade=-1;return;}
        // Taking the queues over costs nothing in the usual frame, where they are empty.
        LongOpenHashSet blocks=NOTHING,lights=NOTHING,unloads=NOTHING;
        synchronized(BlockLights.class){
            if(!BLOCKS.isEmpty()){blocks=BLOCKS;BLOCKS=new LongOpenHashSet();}
            if(!LIGHT.isEmpty()){lights=LIGHT;LIGHT=new LongOpenHashSet();}
            if(!UNLOADED.isEmpty()){unloads=UNLOADED;UNLOADED=new LongOpenHashSet();}
        }
        int reach=Math.max(2,Math.min(14,requestedRange));
        int nx=Math.floorDiv((int)Math.floor(x),16),ny=Math.floorDiv((int)Math.floor(y),16),nz=Math.floorDiv((int)Math.floor(z),16),nr=Math.min(mc.options.getEffectiveRenderDistance()+1,reach);
        // Climbing or descending into another section, or a new range, moves the cube's vertical extent.
        boolean modified=ny!=cy||reach!=range;cy=ny;range=reach;
        if(next!=level||lastOwner!=own){cache.clear();resident.clear();previous=new LongOpenHashSet();slots=new Long2IntOpenHashMap();uploadedFade=-1;level=next;lastOwner=own;cx=Integer.MIN_VALUE;modified=true;}
        var light=next.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        for(LongIterator it=unloads.iterator();it.hasNext();){long chunk=it.nextLong();if(resident.containsKey(chunk)){drop(chunk);modified=true;}}
        // Moving into another chunk (or a new render distance) only adds and drops the chunks at the range's edge.
        if(nx!=cx||nz!=cz||nr!=radius){
            cx=nx;cz=nz;radius=nr;
            for(LongIterator it=resident.keySet().iterator();it.hasNext();){
                long chunk=it.nextLong();if(inRange((int)(chunk>>32),(int)chunk))continue;
                for(long key:resident.get(chunk))cache.remove(key);it.remove();modified=true;
            }
            for(int zz=cz-nr;zz<=cz+nr;zz++)for(int xx=cx-nr;xx<=cx+nr;xx++)if(!resident.containsKey(chunk(xx,zz))&&load(next,xx,zz,light))modified=true;
        }
        for(LongIterator it=blocks.iterator();it.hasNext();){
            long key=it.nextLong();int sx=SectionPos.x(key),sz=SectionPos.z(key);if(!inRange(sx,sz))continue;
            long chunk=chunk(sx,sz);
            if(!resident.containsKey(chunk)){if(load(next,sx,sz,light))modified=true;continue;} // a chunk that loaded in range
            // Stale here means snapshotted during this update already (a freshly loaded chunk queues all its sections).
            Snapshot old=cache.get(key);if(old==null||old.stale())continue;
            LevelChunk owner=next.getChunkSource().getChunk(sx,sz,ChunkStatus.FULL,false);if(owner==null)continue;
            LevelChunkSection section=owner.getSection(next.getSectionIndexFromSectionY(SectionPos.y(key)));
            cache.put(key,new Snapshot(section,old.cells(),source(section),lit(light,key),old.extra(),true));modified=true;
        }
        for(LongIterator it=lights.iterator();it.hasNext();){
            long key=it.nextLong();Snapshot old=cache.get(key);if(old==null)continue;
            boolean now=lit(light,key);if(now!=old.lit()){cache.put(key,old.lit(now));modified=true;}
        }
        if(!modified)return;
        LongOpenHashSet active=new LongOpenHashSet(),affected=new LongOpenHashSet(),coefficients=new LongOpenHashSet();
        for(Long2ObjectMap.Entry<Snapshot> entry:cache.long2ObjectEntrySet())if((entry.getValue().source()||entry.getValue().lit())&&Math.abs(SectionPos.y(entry.getLongKey())-cy)<=range)active.add(entry.getLongKey());
        // One section halo includes blockers and light-path neighbours at resident boundaries.
        LongOpenHashSet domain=new LongOpenHashSet(active);
        for(LongIterator it=active.iterator();it.hasNext();){long key=it.nextLong();int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
            for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                long k=SectionPos.asLong(sx+dx,sy+dy,sz+dz);if(cache.containsKey(k))domain.add(k);
            }
        }
        boolean topology=!domain.equals(previous);
        if(topology){
            for(LongIterator it=domain.iterator();it.hasNext();){long key=it.nextLong();if(!previous.contains(key))affected.add(key);}
            for(LongIterator it=previous.iterator();it.hasNext();){
                long key=it.nextLong();if(domain.contains(key))continue;
                int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
                for(int[] delta:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}){long k=SectionPos.asLong(sx+delta[0],sy+delta[1],sz+delta[2]);if(domain.contains(k))affected.add(k);}
            }
        }
        for(LongIterator it=domain.iterator();it.hasNext();){
            long key=it.nextLong();Snapshot old=cache.get(key);
            if(old.cells()==null||old.stale()){
                int[] grid=new int[4096];LevelChunkSection sec=old.section();boolean extra=false;
                for(int j=0;j<4096;j++){int cell=Palette.CELLS[Block.BLOCK_STATE_REGISTRY.getId(sec.getBlockState(j&15,j>>8,j>>4&15))];grid[j]=cell;extra|=(cell&(1<<24))!=0;}
                if(!Arrays.equals(grid,old.cells())){coefficients.add(key);affected.add(key);}
                cache.put(key,new Snapshot(sec,grid,old.source(),old.lit(),extra,false));
            }
        }
        // Stale sections outside the field drop their grid; it is rebuilt if they rejoin.
        for(Long2ObjectMap.Entry<Snapshot> entry:cache.long2ObjectEntrySet()){Snapshot s=entry.getValue();if(s.stale()&&!domain.contains(entry.getLongKey()))entry.setValue(new Snapshot(s.section(),null,s.source(),s.lit(),false,false));}
        int fade=(range-1)*16;
        if(!topology&&coefficients.isEmpty()&&fade==uploadedFade)return;
        // Sixteen Jacobi steps from zero have a dependency radius of at most 15 cells. Changed sections plus one
        // section ring contain every affected output. A second ring supplies the zero-initialized computation halo;
        // its temporary result is discarded, so cached boundary light cannot extend the transport window.
        LongOpenHashSet output=expand(affected,domain),work=expand(output,domain);
        int n=domain.size();
        if(n==0){OwnNative.blockLightUpload(own,0,0);previous=domain;slots=new Long2IntOpenHashMap();uploadedFade=fade;return;}
        boolean extra=false;for(LongIterator it=domain.iterator();it.hasNext();)extra|=cache.get(it.nextLong()).extra();
        if(topology){
            long[] keys=domain.toLongArray();LongArrays.quickSort(keys);
            int capacity=1;while(capacity<n*2)capacity*=2;
            int table=16,coords=table+capacity*4,neighbors=coords+n*4,workAt=neighbors+n*6,outputAt=workAt+n;
            int cells=outputAt+n,lightOffset=(cells+n*4096+3)&~3;long bytes=(long)lightOffset*4;
            var words=buffer(bytes).asIntBuffer();long address=MemoryUtil.memAddress(packet);
            MemoryUtil.memSet(address,0,(long)cells*4);
            long padding=((long)cells+n*4096L)*4;MemoryUtil.memSet(address+padding,0,bytes-padding);
            words.put(0,n).put(1,capacity-1).put(2,cells).put(3,lightOffset).put(4,table).put(5,coords).put(6,neighbors)
                .put(7,extra?1:0).put(8,workAt).put(9,work.size()).put(10,1).put(11,fade).put(12,outputAt).put(13,output.size()).put(14,++generation);
            Long2IntOpenHashMap nextSlots=new Long2IntOpenHashMap(n);for(int i=0;i<n;i++)nextSlots.put(keys[i],i+1);
            int wi=0,oi=0;int[][] delta={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for(int i=0;i<n;i++){
                long key=keys[i];if(work.contains(key))words.put(workAt+wi++,i+1);if(output.contains(key))words.put(outputAt+oi++,i+1);
                int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key),at=(sx*73856093^sy*19349663^sz*83492791)&(capacity-1);
                while(words.get(table+at*4+3)!=0)at=(at+1)&(capacity-1);
                words.put(table+at*4,sx).put(table+at*4+1,sy).put(table+at*4+2,sz).put(table+at*4+3,i+1);
                words.put(coords+i*4,sx).put(coords+i*4+1,sy).put(coords+i*4+2,sz);
                for(int d=0;d<6;d++)words.put(neighbors+i*6+d,nextSlots.getOrDefault(SectionPos.asLong(sx+delta[d][0],sy+delta[d][1],sz+delta[d][2]),0));
                words.put(cells+i*4096,cache.get(key).cells());
            }
            OwnNative.blockLightUpload(own,MemoryUtil.memAddress(packet),bytes);slots=nextSlots;
        }else{
            // No sorting, rebuilding the section table or copying unchanged coefficient grids. Range-only updates contain
            // just the 64-byte header; native code merges the small lists into its persistent metadata.
            int workAt=16,outputAt=workAt+work.size(),records=outputAt+output.size();long bytes=((long)records+coefficients.size()*4097L)*4;
            var words=buffer(bytes).asIntBuffer();MemoryUtil.memSet(MemoryUtil.memAddress(packet),0,64);
            words.put(0,n).put(2,records).put(3,(int)(bytes/4)).put(4,coefficients.size()).put(7,extra?1:0)
                .put(8,workAt).put(9,work.size()).put(10,1).put(11,fade).put(12,outputAt).put(13,output.size()).put(14,generation).put(15,DELTA);
            int i=workAt;for(LongIterator it=work.iterator();it.hasNext();)words.put(i++,slots.get(it.nextLong()));
            i=outputAt;for(LongIterator it=output.iterator();it.hasNext();)words.put(i++,slots.get(it.nextLong()));
            i=records;for(LongIterator it=coefficients.iterator();it.hasNext();){long key=it.nextLong();words.put(i++,slots.get(key));words.put(i,cache.get(key).cells());i+=4096;}
            OwnNative.blockLightUpload(own,MemoryUtil.memAddress(packet),bytes);
        }
        previous=domain;uploadedFade=fade;
    }
    private ByteBuffer buffer(long bytes){
        if(bytes>Integer.MAX_VALUE)throw new IllegalStateException("Colored light volume exceeds Metal buffer indexing");
        if(packet==null||packet.capacity()<bytes){if(packet!=null)MemoryUtil.memFree(packet);packet=MemoryUtil.memAlloc((int)Math.min(Integer.MAX_VALUE,bytes+bytes/4));}
        return packet;
    }
    private static LongOpenHashSet expand(LongOpenHashSet seeds,LongOpenHashSet domain){
        LongOpenHashSet result=new LongOpenHashSet();
        for(LongIterator it=seeds.iterator();it.hasNext();){long key=it.nextLong();int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
            for(int dy=-RING;dy<=RING;dy++)for(int dz=-RING;dz<=RING;dz++)for(int dx=-RING;dx<=RING;dx++){
                long k=SectionPos.asLong(sx+dx,sy+dy,sz+dz);if(domain.contains(k))result.add(k);
            }
        }
        return result;
    }
}
