package siliconshaders.metal.fast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;

/**
 * Smooth lighting for water and lava (Video Settings → Smooth Lighting). Vanilla lights each fluid face with one value,
 * so torchlight on water shows block-sized steps; here every corner averages the light of the four blocks around it in
 * the plane the face looks into, using the fluid renderer's own light rule per sample and skipping full opaque blocks,
 * as block smooth lighting does. siliconshaders's native water reads this vertex light too. Chunk builder threads: one context
 * each. -Dsiliconshaders.fast.fluidLight=false keeps vanilla.
 */
public final class FastFluidLight {
    public static final boolean ON=!"false".equals(System.getProperty("siliconshaders.fast.fluidLight"));
    /** The block being tessellated: its level, position and its section's origin (vertex coordinates are section-local). */
    public static final class Context {
        public BlockAndTintGetter level;public int x,y,z,ox,oy,oz;public final BlockPos.MutableBlockPos sample=new BlockPos.MutableBlockPos();
    }
    private static final ThreadLocal<Context> CURRENT=ThreadLocal.withInitial(Context::new);
    private static final ThreadLocal<Boolean> ACTIVE=ThreadLocal.withInitial(()->false);
    private FastFluidLight() {}

    public static void begin(BlockAndTintGetter level,BlockPos pos) {
        boolean on=ON&&smooth();ACTIVE.set(on);if(!on)return;
        Context c=CURRENT.get();c.level=level;c.x=pos.getX();c.y=pos.getY();c.z=pos.getZ();c.ox=c.x&~15;c.oy=c.y&~15;c.oz=c.z&~15;
    }
    public static void end() { ACTIVE.set(false); }
    /** The current block's context while smooth fluid lighting applies, else null. */
    public static Context context() { return ACTIVE.get()?CURRENT.get():null; }

    private static boolean smooth() {
        try { var options=Minecraft.getInstance().options;return options!=null&&options.ambientOcclusion().get(); }
        catch(RuntimeException failure) { return false; }
    }

    /** Face axis (0 x, 1 y, 2 z) and outward sign from the corners' plane and where it lies in the block. */
    public static int axis(float nx,float ny,float nz) {
        float ax=Math.abs(nx),ay=Math.abs(ny),az=Math.abs(nz);return ay>=ax&&ay>=az?1:ax>=az?0:2;
    }
    /** The layer of blocks the face looks into: the fluid block itself for its top (vanilla adds the block above), else the neighbour. */
    public static int layer(Context c,int axis,float local) {
        int block=axis==0?c.x:axis==1?c.y:c.z,inside=block&15;float frac=local-inside;
        if(axis==1)return frac<.01f?block-1:block;
        return frac>.5f?block+1:block-1;
    }
    /** Average of packed light values (block light in the low, sky light in the high 16 bits); n of them. */
    public static int average(int blockSum,int skySum,int n) { return (blockSum+n/2)/n|((skySum+n/2)/n)<<16; }
}
