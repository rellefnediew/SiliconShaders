package siliconshaders.metal.sun;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Camera frustum swept toward the sun. Independent half-space elimination; no camera-only caster rejection. */
public final class SunCulling {
    public static final int MAX_PLANES=16,BYTES=16+MAX_PLANES*16;
    public static final float NEAR_KEEP=8;
    private final double[][] base=new double[6][4];
    private final double[] lightDot=new double[6];
    private final float[] planes=new float[MAX_PLANES*4];
    private int count;

    public void clear() { count=0; }
    public int count() { return count; }
    public static float padding(int size,int distance,int levels) {
        int farSize=SunFrames.cascadeSize(size,levels-1);
        return Math.max(2,3.0f*2*distance/Math.max(1,farSize));
    }
    public void update(Matrix4fc clip,float sx,float sy,float sz,float padding) {
        clear();
        double length=Math.sqrt((double)sx*sx+(double)sy*sy+(double)sz*sz);
        if(!Double.isFinite(length)||length<1e-6||!Float.isFinite(clip.determinant())||clip.determinant()==0||!Float.isFinite(padding)||padding<0)return;
        double x=sx/length,y=sy/length,z=sz/length;
        for(int i=0;i<6;i++) {
            int axis=i/2;double sign=(i&1)==0?1:-1;
            // The -1 near plane conservatively includes zero-to-one clip depth as well.
            double norm=0;
            for(int j=0;j<4;j++){base[i][j]=clip.get(j,3)+sign*clip.get(j,axis);if(!Double.isFinite(base[i][j])){clear();return;}if(j<3)norm+=base[i][j]*base[i][j];}
            norm=Math.sqrt(norm);
            if(norm<1e-12){lightDot[i]=Double.NaN;continue;} // e.g. an infinite far plane
            for(int j=0;j<4;j++)base[i][j]/=norm;
            lightDot[i]=base[i][0]*x+base[i][1]*y+base[i][2]*z;
        }
        // A caster c shadows a receiver c-t*sun, t>=0. Eliminate t from the six inequalities.
        // Positive/parallel planes survive; positive-negative pairs bound the swept silhouette.
        for(int i=0;i<6;i++)if(lightDot[i]>=0) {
            if(!add(base[i][0],base[i][1],base[i][2],base[i][3],padding))return;
            if(lightDot[i]>0)for(int j=0;j<6;j++)if(lightDot[j]<0) {
                double a=-lightDot[j],b=lightDot[i];
                if(!add(a*base[i][0]+b*base[j][0],a*base[i][1]+b*base[j][1],a*base[i][2]+b*base[j][2],a*base[i][3]+b*base[j][3],padding))return;
            }
        }
    }
    private boolean add(double x,double y,double z,double w,float padding) {
        double norm=Math.sqrt(x*x+y*y+z*z);
        if(norm<1e-8)return true; // Dropping a redundant/ill-conditioned constraint is conservative.
        if(count==MAX_PLANES||!Float.isFinite((float)(w/norm+padding))){clear();return false;}
        int at=count++*4;
        planes[at]=(float)(x/norm);planes[at+1]=(float)(y/norm);planes[at+2]=(float)(z/norm);planes[at+3]=(float)(w/norm+padding);
        return true;
    }
    public boolean visible(double loX,double loY,double loZ,double hiX,double hiY,double hiZ) {
        if(!Double.isFinite(loX+loY+loZ+hiX+hiY+hiZ)||loX>hiX||loY>hiY||loZ>hiZ)return true;
        double x=Math.max(loX,Math.min(0,hiX)),y=Math.max(loY,Math.min(0,hiY)),z=Math.max(loZ,Math.min(0,hiZ));
        if(x*x+y*y+z*z<=NEAR_KEEP*NEAR_KEEP)return true;
        for(int i=0;i<count;i++) {
            int at=i*4;float nx=planes[at],ny=planes[at+1],nz=planes[at+2];
            if(nx*(nx>=0?hiX:loX)+ny*(ny>=0?hiY:loY)+nz*(nz>=0?hiZ:loZ)+planes[at+3]<0)return false;
        }
        return true;
    }
    public void write(long address) {
        MemoryUtil.memSet(address,0,BYTES);MemoryUtil.memPutInt(address,count);MemoryUtil.memPutFloat(address+4,NEAR_KEEP);
        for(int i=0;i<count*4;i++)MemoryUtil.memPutFloat(address+16+i*4L,planes[i]);
    }
}
