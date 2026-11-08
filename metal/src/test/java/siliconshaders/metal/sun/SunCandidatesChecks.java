package siliconshaders.metal.sun;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class SunCandidatesChecks {
    private static void check(boolean condition,String reason) { if(!condition)throw new AssertionError(reason); }
    public static void main(String[] args) {
        try(MemoryStack stack=MemoryStack.create(256*1024).push()) {
            long sections=stack.ncalloc(16,4096,32),output=stack.ncalloc(4,4096,4);
            var sun=new Vector3f(.6f,.8f,0);var view=new Matrix4f().perspective((float)Math.toRadians(70),1.6f,.05f,512,true);
            var culling=new SunCulling();culling.update(view,sun.x,sun.y,sun.z,2);
            var light=SunProjection.create(sun,2_000_000.25,8,0,1024,64);
            int slot=0;
            for(int y=-8;y<8;y++)for(int z=-8;z<8;z++)for(int x=-8;x<8;x++) {
                long p=sections+slot++*32L;
                MemoryUtil.memPutInt(p,2_000_000+x*16);MemoryUtil.memPutInt(p+4,y*16);MemoryUtil.memPutInt(p+8,z*16);MemoryUtil.memPutInt(p+20,1);
            }
            int count=SunCandidates.write(output,sections,4096,light,culling,2_000_000.25,8,0,64);
            boolean[] kept=new boolean[4096];for(int i=0;i<count;i++) {
                int index=MemoryUtil.memGetInt(output+i*4L);check(index>=0&&index<4096&&!kept[index],"Invalid/duplicate section");kept[index]=true;
            }
            // Independent reference of the existing GPU's section-level test.
            int accepted=0;
            for(int i=0;i<4096;i++) {
                long p=sections+i*32L;float x=(float)(MemoryUtil.memGetInt(p)-2_000_000)-.25f,y=MemoryUtil.memGetInt(p+4)-8,z=MemoryUtil.memGetInt(p+8);
                if(!culling.visible(x,y,z,x+16,y+16,z+16))continue;
                var q=light.transformPosition(x+8,y+8,z+8,new Vector3f());float margin=28.0f/64;
                if(Math.abs(q.x)>1+margin||Math.abs(q.y)>1+margin||q.z< -margin||q.z>1+margin)continue;
                accepted++;check(kept[i],"CPU rejected a potentially relevant caster");
            }
            check(count>0&&count<4096&&count>=accepted,"CPU selection did not reduce work conservatively");
            for(int i=0;i<4096;i++)MemoryUtil.memPutInt(sections+i*32L+20,0);
            check(SunCandidates.write(output,sections,4096,light,culling,2_000_000.25,8,0,64)==0,"Empty slots retained");
            System.out.println("PASS: CPU coarse candidates preserve all reference casters, reduce 4096 cached sections to "+count+", handle large coordinates and empty scenes");
        }
    }
}
