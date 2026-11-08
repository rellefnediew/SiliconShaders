package siliconshaders.metal.sun;

import com.mojang.blaze3d.vertex.QuadInstance;
import java.util.Arrays;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Static glass geometry copied once while the normal chunk mesh is built. 24 bytes per vertex. */
public final class SunGlassQuads {
    private int[] words=new int[96];
    private int count;
    public void clear() { count=0; }
    public int[] copy() { return Arrays.copyOf(words,count); }
    public static boolean isGlass(BlockState state) {
        return state.is(Blocks.GLASS)||state.is(Blocks.GLASS_PANE)||state.getBlock() instanceof StainedGlassBlock||state.getBlock() instanceof StainedGlassPaneBlock;
    }
    private void vertex(float x,float y,float z,float u,float v,int abgr) {
        if(count+6>words.length)words=Arrays.copyOf(words,words.length*2);
        words[count++]=Float.floatToRawIntBits(x);words[count++]=Float.floatToRawIntBits(y);words[count++]=Float.floatToRawIntBits(z);
        words[count++]=Float.floatToRawIntBits(u);words[count++]=Float.floatToRawIntBits(v);words[count++]=abgr;
    }
    public void put(float x,float y,float z,BakedQuad quad,QuadInstance instance) {
        for(int corner=0;corner<4;corner++) {
            var p=quad.position(corner);long uv=quad.packedUV(corner);int c=instance.getColor(corner);
            int abgr=c&0xFF00FF00|(c&0xFF0000)>>16|(c&0xFF)<<16;
            vertex(x+p.x(),y+p.y(),z+p.z(),Float.intBitsToFloat((int)(uv>>32)),Float.intBitsToFloat((int)uv),abgr);
        }
    }
    public void put(float[] positions,float[] uv,int[] colors) {
        for(int c=0;c<4;c++)vertex(positions[c*3],positions[c*3+1],positions[c*3+2],uv[c*2],uv[c*2+1],colors[c]);
    }
}
