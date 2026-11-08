package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryUtil;

/** Eight finite emitters sampled from the same lit silhouette as the sky. Rebuilt only when its geometry changes. */
final class MoonEmission {
    static final int SAMPLES=8,FLOATS=4+SAMPLES*4,GRID=64;
    private record Cached(float phase,int shape,float cos,float sin,float softness,float[] packet) {
        boolean matches(float p,int s,float c,float n,float w){return phase==p&&shape==s&&cos==c&&sin==n&&softness==w;}
    }
    private static volatile Cached cached;
    private MoonEmission(){}
    static void write(long out,float phase,int shape,float cos,float sin,float softness) {
        Cached c=cached;
        if(c==null||!c.matches(phase,shape,cos,sin,softness))cached=c=build(phase,shape,cos,sin,softness);
        for(int i=0;i<FLOATS;i++)MemoryUtil.memPutFloat(out+4L*i,c.packet[i]);
    }
    private static Cached build(float phase,int shape,float cos,float sin,float softness) {
        double[] weight=new double[SAMPLES],sx=new double[SAMPLES],sy=new double[SAMPLES],square=new double[SAMPLES],best=new double[SAMPLES];
        java.util.Arrays.fill(best,Double.POSITIVE_INFINITY);
        float[] packet=new float[FLOATS],coverage=new float[GRID*GRID];int fullArea=0;
        double cp=phase<0?1:Math.cos(phase),sp=Math.sin(phase);
        for(int y=0;y<GRID;y++)for(int x=0;x<GRID;x++) {
            double qx=(x+.5)*2/GRID-1,qy=(y+.5)*2/GRID-1;
            double metric=shape==0?Math.hypot(qx,qy):shape==1?Math.max(Math.abs(qx),Math.abs(qy)):Math.abs(qx)+Math.abs(qy);
            if(metric>1)continue;fullArea++;
            double dx=qx,dy=qy;
            if(shape!=0) {
                double u=shape==1?qx:qx+qy,v=shape==1?qy:qy-qx;
                double a=u*Math.sqrt(Math.max(0,1-.5*v*v)),b=v*Math.sqrt(Math.max(0,1-.5*u*u));
                dx=shape==1?a:(a-b)/Math.sqrt(2);dy=shape==1?b:(a+b)/Math.sqrt(2);
            }
            double px=dx*cos+dy*sin,py=-dx*sin+dy*cos;
            double distance=(sp>=0?px:-px)+cp*Math.sqrt(Math.max(0,1-py*py));
            double width=Math.max(.0001,softness*.45*Math.abs(sp)),t=Math.clamp((distance+width)/(2*width),0,1);
            float light=cp>.999999?1:cp<-.999999?0:(float)(t*t*(3-2*t));coverage[y*GRID+x]=light;
            int bin=(y*4/GRID)*2+x*2/GRID;weight[bin]+=light;sx[bin]+=qx*light;sy[bin]+=qy*light;square[bin]+=(qx*qx+qy*qy)*light;
        }
        // The weighted centroid of a crescent can lie in its dark interior. Pick an actual lit grid point per bin.
        for(int y=0;y<GRID;y++)for(int x=0;x<GRID;x++) {
            float light=coverage[y*GRID+x];if(light<=.001)continue;int bin=(y*4/GRID)*2+x*2/GRID;
            double qx=(x+.5)*2/GRID-1,qy=(y+.5)*2/GRID-1;
            double ox=qx-sx[bin]/weight[bin],oy=qy-sy[bin]/weight[bin],d=ox*ox+oy*oy;
            if(d<best[bin]){best[bin]=d;packet[4+bin*4]=(float)qx;packet[5+bin*4]=(float)qy;}
        }
        double total=0,cx=0,cy=0;int count=0;
        for(int i=0;i<SAMPLES;i++)if(weight[i]>0&&Double.isFinite(best[i])) {
            float x=packet[4+i*4],y=packet[5+i*4];
            packet[6+i*4]=(float)(weight[i]/fullArea);
            packet[7+i*4]=(float)Math.sqrt(Math.max(0,square[i]/weight[i]-2*(x*sx[i]+y*sy[i])/weight[i]+x*x+y*y));
            total+=weight[i];cx+=sx[i];cy+=sy[i];count++;
        }
        if(total>0){packet[0]=(float)(cx/total);packet[1]=(float)(cy/total);packet[2]=(float)(total/fullArea);packet[3]=count;}
        return new Cached(phase,shape,cos,sin,softness,packet);
    }
}
