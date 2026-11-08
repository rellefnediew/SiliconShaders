package siliconshaders.metal.sun;

import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class SunCullingChecks {
    public static void main(String[] args) { run(); }
    public static void run() {
        var cull=new SunCulling();var view=new Matrix4f().perspective((float)Math.toRadians(75),1.6f,0.05f,256,true);
        cull.update(view,0,0.8f,-0.6f,2);
        require(!cull.visible(-1,-1,64,1,1,66),"Irrelevant rear caster retained while looking toward the sun");
        cull.update(view,0,0.8f,0.6f,2);
        require(cull.visible(-1,47,19,1,49,21),"Rear caster capable of shadowing a visible receiver was removed");
        require(cull.visible(-1,-1,1,1,1,2),"Near-player guard was lost");
        require(cull.visible(Double.NaN,0,0,1,1,1),"Unknown bounds were rejected");
        var random=new Random(563431);
        int receivers=0,rejected=0;
        for(int camera=0;camera<60;camera++) {
            var rotation=new Matrix4f().rotateXYZ(random.nextFloat()*2-1,random.nextFloat()*6,random.nextFloat()*.4f-.2f);
            for(boolean zeroToOne:new boolean[]{false,true}) {
                var projection=camera%3==0?new Matrix4f().setOrtho(-40,40,-25,25,.05f,256,zeroToOne):new Matrix4f().perspective(.6f+random.nextFloat(),.6f+random.nextFloat()*2,.05f,256,zeroToOne);
                var clip=projection.mul(rotation,new Matrix4f());
                var inverse=new Matrix4f(clip).invert();
                var sun=new Vector3f(random.nextFloat()*2-1,random.nextFloat(),random.nextFloat()*2-1).normalize();
                cull.update(clip,sun.x,sun.y,sun.z,.05f);
                require(cull.count()>0&&cull.count()<=13,"Invalid extruded frustum plane count");
                for(int i=0;i<200;i++) {
                    float nx=random.nextFloat()*2-1,ny=random.nextFloat()*2-1;
                    var q=new Vector4f(nx,ny,zeroToOne?0:-1,1);inverse.transform(q);q.div(q.w);
                    var far=new Vector4f(nx,ny,1,1);inverse.transform(far);far.div(far.w);
                    q.lerp(far,random.nextFloat());
                    float t=random.nextFloat()*1024;
                    double x=q.x+t*sun.x,y=q.y+t*sun.y,z=q.z+t*sun.z;
                    require(cull.visible(x-.01,y-.01,z-.01,x+.01,y+.01,z+.01),"A true visible-receiver caster was rejected");receivers++;
                    double rx=random.nextFloat()*1024-512,ry=random.nextFloat()*512-256,rz=random.nextFloat()*1024-512;
                    if(!cull.visible(rx,ry,rz,rx+1,ry+1,rz+1))rejected++;
                }
            }
        }
        require(rejected>5000,"Culling did not remove a meaningful number of irrelevant boxes");
        cull.update(new Matrix4f().zero(),0,1,0,2);require(cull.count()==0,"Invalid projection did not disable culling safely");
        cull.update(view,Float.NaN,1,0,2);require(cull.count()==0,"Invalid solar direction enabled culling");
        require(SunCulling.padding(512,256,4)>=6&&SunCulling.padding(4096,64,2)==2,"Coarse filter guard did not scale");
        System.out.printf("PASS: swept-frustum culling retains %d real caster rays, near guard and invalid-input fallback; rejects %d irrelevant boxes%n",receivers,rejected);
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
