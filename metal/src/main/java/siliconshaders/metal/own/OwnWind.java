package siliconshaders.metal.own;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.lwjgl.system.MemoryUtil;

/** Wind attributes occupy the ten unused header bits of an existing 40-byte quad record.
 * Position/UV selections, colors, light coordinates and stub pointers remain unchanged. */
public final class OwnWind {
    public static final float BOUNDS=0.15f;
    public static final int SHIFT=21, MASK=1023<<SHIFT;
    private static final Set<String> PLANTS=Set.of("short_grass","tall_grass","fern","large_fern",
        "dead_bush","firefly_bush","sweet_berry_bush");
    private static final boolean ON=Boolean.parseBoolean(System.getProperty("siliconshaders.sun.wind","true"));
    private OwnWind() {}

    public static boolean enabled() {
        return OwnMesher.ON && OwnQrec.ON && ON;
    }
    /** 1 leaves, 2 rooted plant, 3/4 lower/upper half of a two-block plant, 5 attached foliage. */
    static int kind(BlockState state) {
        if(state.isAir())return 0;
        if(state.is(BlockTags.LEAVES))return 1;
        String path=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(path.equals("vine")||path.equals("pale_hanging_moss"))return 5;
        if(!(state.is(BlockTags.FLOWERS)||state.getBlock() instanceof CropBlock||PLANTS.contains(path)))return 0;
        if(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.UPPER?4:3;
        return 2;
    }
    /** Two type bits and two bits per corner: weights 0, 1/2, 1 (fourth code reserved). */
    static int header(int kind,int blockY,float[] position) {
        if(kind==0)return 0;
        int result=kind==1?1:kind==5?3:2;
        float root=blockY-(kind==4?1:0),height=kind==3||kind==4?2:1;
        for(int c=0;c<4;c++) {
            int weight=kind==1||kind==5?2:Math.clamp(Math.round(2*(position[c*3+1]-root)/height),0,2);
            result|=weight<<(2+c*2);
        }
        return result<<SHIFT;
    }
    /** Unused fourth components, shared by terrain and every light-space caster draw. */
    public static void packet(long p,int levels,float time,float strength) {
        for(int i=0;i<levels;i++) {
            long at=p+siliconshaders.metal.sun.SunFrames.cascadeOffset(i);
            MemoryUtil.memPutFloat(at+76,strength);MemoryUtil.memPutFloat(at+92,time);
        }
    }
    public static final class Clock {
        private long previous;
        private double phase;
        private float wet,storm;
        public float update(long now,boolean paused,float rain,float thunder) {
            double dt=previous==0?0:Math.clamp((now-previous)*1e-9,0,0.25);previous=now;
            if(!paused) {
                float response=(float)(1-Math.exp(-dt/2));
                wet+=(Math.clamp(rain,0,1)-wet)*response;
                storm+=(Math.clamp(thunder,0,1)-storm)*response;
                phase=(phase+dt*(1+0.35*wet+0.15*storm))%4096;
            }
            return (float)phase;
        }
        public float strength() { return 1+0.5f*wet+0.25f*storm; }
    }
}
