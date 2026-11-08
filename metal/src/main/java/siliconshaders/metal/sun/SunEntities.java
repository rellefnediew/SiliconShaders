package siliconshaders.metal.sun;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.ArrayList;
import java.util.List;
import siliconshaders.metal.MetalBridge;
import siliconshaders.metal.mixin.pack.SunDrawAccess;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Reuses this frame's already posed/uploaded model and item geometry. Render thread only. */
public final class SunEntities {
    public static final int BATCH_BYTES=176,MAX_BATCHES=4096;
    public static boolean collecting,uploading;
    private record Batch(StagedVertexBuffer staging,StagedVertexBuffer.Draw draw,PreparedRenderType type) { }
    private static final List<Batch> batches=new ArrayList<>();
    private static final Matrix4f model=new Matrix4f(),modelView=new Matrix4f();
    private SunEntities() {}

    public static void begin(boolean enabled) { collecting=enabled;if(!SunSelfShadow.preparing)batches.clear(); }

    public static void capture(FeatureFrameContext context,List<StagedVertexBuffer.Draw> draws,List<PreparedRenderType> types) {
        if(!collecting)return;
        for(int i=0;i<draws.size()&&batches.size()<MAX_BATCHES;i++) {
            var type=types.get(i);String name=type.name();
            if(name.contains("glint")||name.contains("eyes")||name.contains("outline")||name.contains("energy_swirl"))continue;
            batches.add(new Batch(context.stagedVertexBuffer(),draws.get(i),type));
        }
    }

    public static int count() { return batches.size(); }

    private static final int MAX_BOUNDS_VERTICES=1<<18;
    private static final float BOUNDS_PADDING=0.0625f;
    private static final org.joml.Vector3f corner=new org.joml.Vector3f();

    /**
     * This frame's posed vertices give the batch's camera-relative box, through the same model matrix the shadow
     * vertex shader uses. False (caller keeps the whole shadow volume) when the vertices are not CPU-visible,
     * the batch is very large, or a value is not finite.
     */
    private static boolean bounds(long target,long vertices,int baseVertex,int vertexCount,int stride,int offset) {
        if(vertices==0||vertexCount<=0||vertexCount>MAX_BOUNDS_VERTICES||baseVertex<0)return false;
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        long at=vertices+(long)baseVertex*stride+offset;
        for(int i=0;i<vertexCount;i++,at+=stride) {
            float x=MemoryUtil.memGetFloat(at),y=MemoryUtil.memGetFloat(at+4),z=MemoryUtil.memGetFloat(at+8);
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        float loX=Float.POSITIVE_INFINITY,loY=loX,loZ=loX,hiX=Float.NEGATIVE_INFINITY,hiY=hiX,hiZ=hiX;
        for(int c=0;c<8;c++) {
            model.transformPosition(corner.set((c&1)!=0?maxX:minX,(c&2)!=0?maxY:minY,(c&4)!=0?maxZ:minZ));
            loX=Math.min(loX,corner.x);loY=Math.min(loY,corner.y);loZ=Math.min(loZ,corner.z);hiX=Math.max(hiX,corner.x);hiY=Math.max(hiY,corner.y);hiZ=Math.max(hiZ,corner.z);
        }
        if(!Float.isFinite(loX+loY+loZ+hiX+hiY+hiZ))return false;
        MemoryUtil.memPutFloat(target,loX-BOUNDS_PADDING);MemoryUtil.memPutFloat(target+4,loY-BOUNDS_PADDING);MemoryUtil.memPutFloat(target+8,loZ-BOUNDS_PADDING);
        MemoryUtil.memPutFloat(target+16,hiX+BOUNDS_PADDING);MemoryUtil.memPutFloat(target+20,hiY+BOUNDS_PADDING);MemoryUtil.memPutFloat(target+24,hiZ+BOUNDS_PADDING);
        return true;
    }

    /** Writes only native handles and layout metadata; vertices remain in the game's existing GPU buffers. */
    public static int write(long target,Object encoder,Matrix4fc inverseView,int distance) {
        int count=0;boolean previousUpload=uploading;uploading=true;
        try { for(var batch:batches) {
            var format=((SunDrawAccess)(Object)batch.draw()).siliconshaders$sunFormat();
            if(!format.contains("Position")||!format.contains("UV0"))continue;
            var position=format.getElement("Position");var uv=format.getElement("UV0");
            if(position.format()!=GpuFormat.RGB32_FLOAT||uv.format()!=GpuFormat.RG32_FLOAT)continue;
            var info=batch.staging().getExecuteInfo(batch.draw());if(info==null||info.indexCount()==0)continue;
            PreparedRenderType.Texture albedo=null;
            for(var texture:batch.type().textures())if(texture.name().equals("Sampler0")){albedo=texture;break;}
            if(albedo==null)continue;
            var transforms=batch.type().dynamicTransforms();long address=MetalBridge.bufferAddress(transforms.buffer());
            if(address==0)continue;
            model.set(inverseView).mul(modelView.setFromAddress(address+transforms.offset()));
            long p=target+(long)count*BATCH_BYTES;MemoryUtil.memSet(p,0,BATCH_BYTES);
            MemoryUtil.memPutLong(p,MetalBridge.useBuffer(encoder,info.vertexBuffer()));
            MemoryUtil.memPutLong(p+8,MetalBridge.useBuffer(encoder,info.indexBuffer()));
            MemoryUtil.memPutLong(p+16,MetalBridge.viewHandle(albedo.textureView()));
            MemoryUtil.memPutLong(p+24,MetalBridge.samplerHandle(albedo.sampler()));
            model.getToAddress(p+32);
            MemoryUtil.memPutInt(p+96,format.getVertexSize());MemoryUtil.memPutInt(p+100,position.offset());MemoryUtil.memPutInt(p+104,uv.offset());
            MemoryUtil.memPutInt(p+108,format.contains("Color")&&format.getElement("Color").format()==GpuFormat.RGBA8_UNORM?format.getElement("Color").offset():-1);
            MemoryUtil.memPutInt(p+112,info.indexCount());MemoryUtil.memPutInt(p+116,info.firstIndex());MemoryUtil.memPutInt(p+120,info.baseVertex());
            MemoryUtil.memPutInt(p+124,info.indexType()==com.mojang.renderpearl.api.pipeline.IndexType.INT?1:0);
            MemoryUtil.memPutFloat(p+136,distance);
            if(!bounds(p+144,MetalBridge.bufferAddress(info.vertexBuffer()),info.baseVertex(),((SunDrawAccess)(Object)batch.draw()).siliconshaders$sunVertexCount(),format.getVertexSize(),position.offset()))
                for(int axis=0;axis<3;axis++){MemoryUtil.memPutFloat(p+144+4*axis,-distance);MemoryUtil.memPutFloat(p+160+4*axis,distance);}
            count++;
        }
        return count;
        } finally { uploading=previousUpload; }
    }
}
