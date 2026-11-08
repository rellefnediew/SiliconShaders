package siliconshaders.metal.sun;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.system.MemoryUtil;

/**
 * A held item's light for the hand light (Complementary's held lighting): the light level of its block
 * (a lava bucket counts as 15) and, with colored items on, a tint for soul, redstone and cold lights.
 * Writes level, red, green, blue; a negative red means "the block light color".
 */
public final class SunHandLight {
    private SunHandLight() {}
    /** The light level of a held item: its block's emission; a lava bucket counts as 15. */
    public static int level(ItemStack stack) {
        if(stack==null||stack.isEmpty())return 0;
        if(stack.is(Items.LAVA_BUCKET))return 15;
        return stack.getItem() instanceof BlockItem item?item.getBlock().defaultBlockState().getLightEmission():0;
    }
    /**
     * The packed light for drawing a held item (and the hand holding it): a light-emitting item glows at full
     * block light while the shader and the hand light are on, like Complementary's emissive held items.
     */
    public static int glow(ItemStack stack,int packedLight) {
        if(!SunLighting.enabled()||SunTuning.get().value(SunTuning.HAND_LIGHT)<0.5f||level(stack)<=0)return packedLight;
        return (packedLight&0xFFFF0000)|0xF0;  // block light 15 (LightTexture.pack: block << 4, sky << 20)
    }
    public static void write(long target,ItemStack stack,boolean colored) {
        int level=level(stack);
        MemoryUtil.memPutFloat(target,level);
        float[] tint=level>0&&colored?tint(stack.getItem()):null;
        MemoryUtil.memPutFloat(target+4,tint==null?-1:tint[0]);MemoryUtil.memPutFloat(target+8,tint==null?-1:tint[1]);MemoryUtil.memPutFloat(target+12,tint==null?-1:tint[2]);
    }
    private static final java.util.Map<Item,float[]> TINTS=new java.util.IdentityHashMap<>();
    private static final float[] NO_TINT={};
    private static float[] tint(Item item) {
        float[] tint=TINTS.computeIfAbsent(item,known->{float[] named=tint(BuiltInRegistries.ITEM.getKey(known).getPath());return named==null?NO_TINT:named;});
        return tint==NO_TINT?null:tint;
    }
    // Display-space tints in Complementary's spirit: soul fire cyan, redstone red, sea lantern/end rod/beacon cold white.
    private static float[] tint(String id) {
        if(id.contains("soul"))return new float[]{0.30f,0.90f,1.00f};
        if(id.startsWith("redstone"))return new float[]{1.00f,0.15f,0.10f};
        if(id.equals("sea_lantern")||id.equals("end_rod")||id.equals("beacon")||id.equals("sea_pickle"))return new float[]{0.80f,0.92f,1.00f};
        if(id.equals("lava_bucket")||id.equals("magma_block"))return new float[]{1.00f,0.45f,0.12f};
        if(id.contains("amethyst"))return new float[]{0.80f,0.50f,1.00f};
        return null;
    }
}
