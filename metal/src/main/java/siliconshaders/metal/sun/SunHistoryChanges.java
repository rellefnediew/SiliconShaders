package siliconshaders.metal.sun;

import org.joml.Matrix4fc;

/** Render-thread section changes, including removed casters, bounded while no world is drawn. */
public final class SunHistoryChanges {
    private final int[] sections=new int[3*1024];
    private int count;
    private boolean overflow;
    private long serial;

    public long serial() { return serial; }

    public void changed(int x,int y,int z) {
        serial++;
        if(count==sections.length) { overflow=true;return; }
        sections[count++]=x;sections[count++]=y;sections[count++]=z;
    }

    /** Match sun_cull's conservative section-center bounds, using this frame's light projection. */
    public boolean consume(Matrix4fc light,double cameraX,double cameraY,double cameraZ,float range) {
        boolean relevant=overflow;
        float margin=28.0f/range;
        for(int i=0;i<count&&!relevant;i+=3) {
            float x=(float)(sections[i]-cameraX)+8,y=(float)(sections[i+1]-cameraY)+8,z=(float)(sections[i+2]-cameraZ)+8;
            float qx=light.m00()*x+light.m10()*y+light.m20()*z+light.m30();
            float qy=light.m01()*x+light.m11()*y+light.m21()*z+light.m31();
            float qz=light.m02()*x+light.m12()*y+light.m22()*z+light.m32();
            relevant=Math.abs(qx)<=1+margin && Math.abs(qy)<=1+margin && qz>=-margin && qz<=1+margin;
        }
        count=0;overflow=false;
        return relevant;
    }
}
