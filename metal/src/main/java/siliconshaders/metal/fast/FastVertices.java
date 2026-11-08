package siliconshaders.metal.fast;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryUtil;

/**
 * Whole-vertex writes for the formats of particles (PARTICLE), GUI text (POSITION_COLOR_TEX_LIGHTMAP) and GUI images
 * (POSITION_TEX_COLOR), the way BufferBuilder's own block/entity fast path writes: beginVertex, then every element at its
 * offset, with BufferBuilder's color and light packing. Offsets come from the format; any other consumer or format keeps
 * the chained vanilla calls. The bytes equal the chained calls' bytes.
 */
public final class FastVertices {
    private static final MethodHandle BEGIN_VERTEX,FORMAT;
    private static final boolean LITTLE=java.nio.ByteOrder.nativeOrder()==java.nio.ByteOrder.LITTLE_ENDIAN;
    /** Element offsets of one supported format; -1 where the format has no such element. */
    public record Layout(VertexFormat format,int position,int uv0,int color,int uv2) {}
    public static final Layout PARTICLE=layout(DefaultVertexFormat.PARTICLE),TEXT=layout(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
        IMAGE=layout(DefaultVertexFormat.POSITION_TEX_COLOR);
    static {
        MethodHandle begin=null,format=null;
        try {
            var lookup=MethodHandles.privateLookupIn(BufferBuilder.class,MethodHandles.lookup());
            begin=lookup.findVirtual(BufferBuilder.class,"beginVertex",MethodType.methodType(long.class));
            format=lookup.findGetter(BufferBuilder.class,"format",VertexFormat.class);
        } catch(ReflectiveOperationException|RuntimeException failure) {
            siliconshaders.metal.Log.warn("[SiliconShaders fast] Direct vertex writes unavailable: "+failure);
        }
        BEGIN_VERTEX=begin;FORMAT=format;
    }
    private FastVertices() {}

    private static Layout layout(VertexFormat format) {
        try {
            int position=offset(format,"Position"),uv0=offset(format,"UV0"),color=offset(format,"Color"),uv2=offset(format,"UV2");
            int written=(position>=0?1:0)+(uv0>=0?1:0)+(color>=0?1:0)+(uv2>=0?1:0);
            // Only formats made of exactly these elements: every byte of the vertex is then written.
            return position>=0&&written==format.getElements().size()?new Layout(format,position,uv0,color,uv2):null;
        } catch(RuntimeException|LinkageError failure) { return null; }
    }
    private static int offset(VertexFormat format,String name) { return format.contains(name)?format.getElement(name).offset():-1; }

    /** The builder when consumer is a BufferBuilder in exactly this layout's format, else null. */
    public static BufferBuilder direct(VertexConsumer consumer,Layout layout) {
        if(layout==null||BEGIN_VERTEX==null||!(consumer instanceof BufferBuilder builder))return null;
        try { return (VertexFormat)FORMAT.invokeExact(builder)==layout.format()?builder:null; }
        catch(Throwable failure) { return null; }
    }

    /** ARGB in BufferBuilder's byte order (putRgba). */
    public static int rgba(int argb) { int abgr=ARGB.toABGR(argb);return LITTLE?abgr:Integer.reverseBytes(abgr); }

    public static void put(BufferBuilder builder,Layout layout,float x,float y,float z,float u,float v,int rgba,int light) {
        long p;
        try { p=(long)BEGIN_VERTEX.invokeExact(builder); }
        catch(RuntimeException|Error failure) { throw failure; }
        catch(Throwable failure) { throw new IllegalStateException(failure); }
        long at=p+layout.position();MemoryUtil.memPutFloat(at,x);MemoryUtil.memPutFloat(at+4,y);MemoryUtil.memPutFloat(at+8,z);
        if(layout.uv0()>=0){MemoryUtil.memPutFloat(p+layout.uv0(),u);MemoryUtil.memPutFloat(p+layout.uv0()+4,v);}
        if(layout.color()>=0)MemoryUtil.memPutInt(p+layout.color(),rgba);
        if(layout.uv2()>=0) {
            if(LITTLE)MemoryUtil.memPutInt(p+layout.uv2(),light);
            else {MemoryUtil.memPutShort(p+layout.uv2(),(short)(light&0xFFFF));MemoryUtil.memPutShort(p+layout.uv2()+2,(short)(light>>>16&0xFFFF));}
        }
    }
}
