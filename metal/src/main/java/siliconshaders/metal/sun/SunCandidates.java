package siliconshaders.metal.sun;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Coarse CPU selection over the renderer's cached section bounds; no depth readback or scene traversal. */
public final class SunCandidates {
    private SunCandidates() {}
    public static int write(long output,long sections,int count,Matrix4fc light,SunCulling culling,
                            double cameraX,double cameraY,double cameraZ,float range) {
        int kept=0;
        float margin=28.0f/range+1e-4f;
        double bx=Math.floor(cameraX),by=Math.floor(cameraY),bz=Math.floor(cameraZ);
        for(int slot=0;slot<count;slot++) {
            long section=sections+slot*32L;
            if(MemoryUtil.memGetInt(section+20)==0)continue;
            // Match the GPU's float camera-relative origins to avoid boundary disagreements.
            float x=(float)(MemoryUtil.memGetInt(section)-bx)+(float)(bx-cameraX);
            float y=(float)(MemoryUtil.memGetInt(section+4)-by)+(float)(by-cameraY);
            float z=(float)(MemoryUtil.memGetInt(section+8)-bz)+(float)(bz-cameraZ);
            if(!culling.visible(x-.01,y-.01,z-.01,x+16.01,y+16.01,z+16.01))continue;
            float cx=x+8,cy=y+8,cz=z+8;
            float qx=light.m00()*cx+light.m10()*cy+light.m20()*cz+light.m30();
            float qy=light.m01()*cx+light.m11()*cy+light.m21()*cz+light.m31();
            float qz=light.m02()*cx+light.m12()*cy+light.m22()*cz+light.m32();
            if(Float.isFinite(qx+qy+qz) && (Math.abs(qx)>1+margin || Math.abs(qy)>1+margin || qz< -margin || qz>1+margin))continue;
            MemoryUtil.memPutInt(output+kept++*4L,slot);
        }
        return kept;
    }
}
