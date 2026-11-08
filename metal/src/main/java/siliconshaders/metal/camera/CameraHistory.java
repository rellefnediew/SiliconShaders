package siliconshaders.metal.camera;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Render-thread camera reprojection only; no world traversal or GPU readback. */
public final class CameraHistory {
    private final Matrix4f previous=new Matrix4f(),inverse=new Matrix4f(),reprojection=new Matrix4f();
    private final Vector3f direction=new Vector3f(),oldDirection=new Vector3f();
    private java.lang.ref.WeakReference<Object> world=new java.lang.ref.WeakReference<>(null);
    private final Vector4f forward=new Vector4f();
    private boolean moving;private double x,y,z;private long last;private int width,height;private boolean valid;
    public boolean update(Matrix4fc clip,double cx,double cy,double cz,Object currentWorld,int w,int h,long now,boolean paused){
        inverse.set(clip).invert();
        boolean finite=clip.isFinite()&&inverse.isFinite()&&Double.isFinite(cx+cy+cz)&&w>0&&h>0;
        inverse.transform(forward.set(0,0,.5f,1));direction.set(forward.x,forward.y,forward.z).normalize();
        double dt=last==0?0:(now-last)*1e-9,dx=cx-x,dy=cy-y,dz=cz-z;
        boolean reuse=finite&&valid&&currentWorld==world.get()&&w==width&&h==height&&!paused&&dt>0&&dt<=.25&&dx*dx+dy*dy+dz*dz<64&&direction.dot(oldDirection)>.8f;
        // Changes in FOV/aspect/near plane invalidate history, independently of camera rotation.
        if(reuse)for(int row=0;row<3;row++){
            double a=0,b=0;for(int col=0;col<3;col++){double u=clip.get(col,row),v=previous.get(col,row);a+=u*u;b+=v*v;}
            if(Math.abs(a-b)>Math.max(1e-5,b*.002))reuse=false;
        }
        reprojection.set(previous).translate((float)dx,(float)dy,(float)dz);
        moving=reuse&&(!previous.equals(clip,1e-6f)||dx*dx+dy*dy+dz*dz>1e-10);
        previous.set(clip);oldDirection.set(direction);x=cx;y=cy;z=cz;last=now;if(world.get()!=currentWorld)world=new java.lang.ref.WeakReference<>(currentWorld);width=w;height=h;valid=finite&&!paused;
        if(!reuse)reprojection.set(clip);
        return reuse;
    }
    public boolean moving(){return moving;}
    public org.joml.Vector3fc direction(){return direction;}
    public Matrix4fc inverse(){return inverse;}public Matrix4fc reprojection(){return reprojection;}
    public void reset(){valid=moving=false;world.clear();last=0;}
}
