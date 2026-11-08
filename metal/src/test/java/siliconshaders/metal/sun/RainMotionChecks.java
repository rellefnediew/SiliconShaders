package siliconshaders.metal.sun;

import java.nio.file.Files;
import java.util.Random;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.joml.Vector3f;

/** Independent kinematic, persistence and sky-basis contracts. No graphics window. */
public final class RainMotionChecks {
    private static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static void close(float a,float b,String why){require(Math.abs(a-b)<1e-4,why+": "+a+" / "+b);}
    public static void main(String[] args) throws Exception {
        var dir=Files.createTempDirectory("rain-motion-");var settings=new SunTuning(dir);float[] t=settings.values();var m=new RainMotion();
        t[RainMotion.WIND]=0;t[RainMotion.WIDTH]=t[RainMotion.LENGTH]=1;t[RainMotion.VARIATION]=0;
        m.frame(t,500,.5f,100.25,80.5,-400.75);m.column(.25f,3.25f);
        require(!m.changesVertices(),"Neutral motion should bypass wrapper");close(m.u(.25f,9),.25f,"Neutral U");close(m.v(17.75f),17.75f,"Neutral V");
        t[RainMotion.WIND]=1;t[RainMotion.ANGLE]=45;t[RainMotion.DIRECTION]=90;t[RainMotion.GUSTS]=0;
        m.frame(t,500,0,0,64,0);m.column(0,5);
        // East wind, viewed south: the billboard's right is west. A four-block fall must move four blocks east.
        close(m.u(0,4),-4,"45 degree world wind projection");close(m.u(4,4),0,"45 degree drop trajectory");
        t[RainMotion.DIRECTION]=270;m.frame(t,500,0,0,64,0);m.column(0,5);close(m.u(0,4),4,"Opposite wind direction");
        t[RainMotion.WIND]=0;t[RainMotion.WIDTH]=.65f;t[RainMotion.LENGTH]=.8f;t[RainMotion.VARIATION]=.75f;
        // Same world column seen from another camera position retains both variation factors, including negative coordinates.
        m.frame(t,50,0,100.25,90,-100.25);m.column(-4.75f,7.75f);float u=m.u(1,2),v=m.v(1);
        m.frame(t,5000,0,102.75,100,-104.75);m.column(-7.25f,12.25f);close(m.u(1,-8),u,"World-anchored width");close(m.v(1),v,"World-anchored length");
        var rng=new Random(11);float min=Float.MAX_VALUE,max=0;
        for(int i=0;i<2000;i++){m.column(rng.nextInt(400)-200,rng.nextInt(400)-200);float a=m.u(1,0);require(Float.isFinite(a)&&Float.isFinite(m.v(100)),"Invalid size variation");min=Math.min(min,a);max=Math.max(max,a);}
        require(max-min>1,"Variation is ineffective");
        t[RainMotion.WIND]=1;t[RainMotion.GUSTS]=1;t[RainMotion.SPEED]=3;
        m.frame(t,5000,.2f,30000000,90,-30000000);m.column(3,5);float a=m.u(1,4);
        m.frame(t,5000,.201f,30000000,90,-30000000);m.column(3,5);require(Math.abs(m.u(1,4)-a)<.01,"Gust discontinuity");
        m.frame(t,5000,.2f,30000000,90,-30000000);m.column(3,5);require(m.u(1,4)==a,"Paused frame drifts");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            long at=stack.nmalloc(16,SunAurora.FLOATS*4);var center=new Vector3f();var right=new Vector3f();var up=new Vector3f();
            for(int bearing=0;bearing<=360;bearing+=15)for(int elevation=5;elevation<=80;elevation+=5) {
                t[SunAurora.DIRECTION]=bearing;t[SunAurora.ELEVATION]=elevation;SunAurora.write(at,t);
                center.setFromAddress(at);right.setFromAddress(at+16);
                // Curved auroras now use the spare Y components for curvature and inverse extent.
                // The orientation vectors remain in XZ; do not include these scalar controls in their norms.
                close(center.y,.08f,"Sky curvature coefficient");close(right.y,(float)Math.sqrt(2),"Sky inverse extent");
                center.y=0;right.y=0;
                close(center.length(),1,"Sky center norm");close(right.length(),1,"Sky tangent norm");
                close(center.dot(right),0,"Sky center/right");
                close(center.y,0,"Azimuth has no elevation clipping");
                close(MemoryUtil.memGetFloat(at+12),(float)(Math.tan(Math.toRadians(elevation))/Math.tan(Math.toRadians(25))),"Unclipped layer height");
                close(center.x,(float)Math.sin(Math.toRadians(bearing)),"World bearing");
            }
        }
        // Real adapter contract: only UV changes, with the same count/positions/color/light/normals.
        var calls=new java.util.ArrayList<String>();var last=new java.util.HashMap<String,Object[]>();
        var delegate=(com.mojang.blaze3d.vertex.VertexConsumer)java.lang.reflect.Proxy.newProxyInstance(
            RainMotionChecks.class.getClassLoader(),new Class[]{com.mojang.blaze3d.vertex.VertexConsumer.class},(proxy,method,arguments)->{calls.add(method.getName());last.put(method.getName(),arguments);return proxy;});
        t[RainMotion.WIND]=0;t[RainMotion.WIDTH]=.5f;t[RainMotion.LENGTH]=2;t[RainMotion.VARIATION]=0;
        m.frame(t,0,0,0,64,0);m.column(0,5);var adapter=new RainVertices(m);var wrapped=adapter.bind(delegate);
        wrapped.addVertex(1,2,3).setColor(0x80332211).setUv(.25f,.5f).setLight(0xf000e0).setNormal(0,1,0);
        require(java.util.Arrays.equals(last.get("addVertex"),new Object[]{1f,2f,3f}),"Rain positions changed");
        require(last.get("setColor")[0].equals(0x80332211)&&last.get("setUv2")[0].equals(0xe0)&&last.get("setUv2")[1].equals(0xf0),"Rain tint/alpha/light changed");
        require(last.get("setUv")[0].equals(.5f)&&last.get("setUv")[1].equals(.25f),"Width/length not applied to real UVs");
        require(java.util.Collections.frequency(calls,"addVertex")==1,"Rain adapter duplicates vertices");adapter.clear();
        require(SunAurora.samples(0)==25&&SunAurora.samples(1)==16&&SunAurora.samples(2)==10,"Aurora sample budgets");
        for(int i:new int[]{RainMotion.ANGLE,RainMotion.DIRECTION,RainMotion.WIDTH,RainMotion.LENGTH,RainMotion.VARIATION,SunAtmosphere.AURORA_DEPTH})settings.set(i,SunTuning.PARAMS.get(i).max());
        var restored=new SunTuning(dir);for(int i=0;i<SunTuning.COUNT;i++)require(Math.abs(restored.value(i)-settings.value(i))<1e-5,"Settings not restored at "+i);
        restored.reset(SunTuning.Group.SKY);require(restored.value(SunAurora.SPEED)==2&&restored.value(RainMotion.ANGLE)==12,"Sky reset defaults");
        try(var paths=Files.walk(dir)){for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}
        System.out.println("PASS: rain off/neutral, world wind direction and trajectory, 2000 stable size variations, smooth/paused gusts, large worlds, 400 overhead orientations and uncapped layer heights, attribute-preserving vertex adapter, settings persistence and reset");
    }
}
