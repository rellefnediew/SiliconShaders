package siliconshaders.metal.own;

import java.util.Random;
import siliconshaders.metal.sun.SunTuning;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** CPU/shader metadata contract and conservative view bounds, including actual ordered mesher output. */
public final class ReflectionReceiverChecks {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static int box(int x,int y,int z){return x+16|(y+16)<<8|(z+16)<<16;}
    public static void main(String[] args) {
        var names=SurfaceMaterials.catalog().profiles();int eligible=0;
        for(int i=0;i<names.size();i++)if(ReflectionReceivers.group(i)!=0)eligible++;
        require(eligible==26,"Shader palette was not parsed or receiver count drifted: "+eligible);
        require(ReflectionReceivers.group(names.indexOf("gold"))==1<<1&&ReflectionReceivers.group(names.indexOf("quartz"))==1<<2&&ReflectionReceivers.group(names.indexOf("diamond"))==1<<5,"Reflection group contract");
        require(ReflectionReceivers.group(0)==0&&ReflectionReceivers.group(names.indexOf("wood"))==0&&ReflectionReceivers.group(255)==ReflectionReceivers.ALL,"Nonreceivers or unknown profile handling");
        float[] tuning=new float[SunTuning.COUNT];for(int i=0;i<tuning.length;i++)tuning[i]=SunTuning.PARAMS.get(i).neutral();
        require(ReflectionReceivers.activeGroups(tuning)==ReflectionReceivers.ALL,"Default groups");
        int at=SunTuning.MATERIAL_START;tuning[at+5]=0;require(ReflectionReceivers.activeGroups(tuning)==((1<<2)|(1<<5)),"Live metal group");tuning[at+19]=0;require(ReflectionReceivers.activeGroups(tuning)==0,"World reflection switch");
        {
            float[] rain=new float[SunTuning.COUNT];for(int i=0;i<rain.length;i++)rain[i]=SunTuning.PARAMS.get(i).neutral();int wet=siliconshaders.metal.sun.SunWeather.WET_SLOT;
            rain[wet]=0;require(!ReflectionReceivers.puddles(rain),"Dry ground kept the pass");
            rain[wet]=.3f;require(!ReflectionReceivers.puddles(rain),"Thin film (no puddles) kept the pass");
            rain[wet]=1;require(ReflectionReceivers.puddles(rain),"Soaked ground culled the puddle reflections");
            rain[siliconshaders.metal.sun.SunWeather.WET_REFLECTION_RADIUS]=0;require(!ReflectionReceivers.puddles(rain),"Zero puddle radius kept the pass");
            rain[siliconshaders.metal.sun.SunWeather.WET_REFLECTION_RADIUS]=32;
            rain[at+19]=0;require(!ReflectionReceivers.puddles(rain),"Puddles traced with world reflections off");
        }
        long packet=MemoryUtil.nmemCalloc(1,64),metadata=MemoryUtil.nmemAlloc(3);
        try {
            var view=new ReflectionReceivers.View();var summary=new ReflectionReceivers.Summary(1<<1,box(-1,-1,-1),box(1,1,1));
            var rng=new Random(4005);int rejected=0,checks=0;
            for(float phase:new float[]{-1,0,1}) {
                Matrix4f clip=new Matrix4f().perspective(1.2f,16f/9,.05f,512,true).translate(phase*.03f,phase*.02f,0).rotateZ(phase*.015f);
                int[] sides={Matrix4fc.PLANE_NX,Matrix4fc.PLANE_PX,Matrix4fc.PLANE_NY,Matrix4fc.PLANE_PY};Vector4f plane=new Vector4f();
                for(int i=0;i<4;i++){clip.frustumPlane(sides[i],plane);plane.getToAddress(packet+i*16L);}view.set(packet);
                require(view.visible(summary,1<<1,0,0,-8)&&!view.visible(summary,1<<1,100,0,-8),"Visible/offscreen classification");
                require(view.visible(ReflectionReceivers.UNKNOWN,1<<1,10000,0,8),"Unknown geometry did not fail open");
                for(int i=0;i<1000;i++) {
                    double x=rng.nextDouble()*200-100,y=rng.nextDouble()*160-80,z=rng.nextDouble()*200-100;
                    boolean outside=false;
                    // Independent clip-coordinate corner oracle; don't reproduce the positive-vertex CPU test.
                    for(int side=0;side<4;side++){boolean all=true;for(int c=0;c<8;c++){
                        Vector4f q=clip.transform(new Vector4f((float)x+((c&1)==0?-1:1),(float)y+((c&2)==0?-1:1),(float)z+((c&4)==0?-1:1),1));
                        float d=side==0?q.w+q.x:side==1?q.w-q.x:side==2?q.w+q.y:q.w-q.y;all&=d<0;
                    }outside|=all;}
                    boolean visible=view.visible(summary,1<<1,x,y,z);if(!outside)require(visible,"A potentially visible receiver was culled");if(!visible)rejected++;checks++;
                }
            }
            var ground=new ReflectionReceivers.Summary(0,0,0,box(0,0,0),box(16,1,16));
            // Invalid view deliberately leaves only the independently testable 3D sphere rejection active.
            MemoryUtil.memPutFloat(packet,Float.NaN);view.set(packet);
            require(view.puddle(ground,32,-8,-1,-8,1),"Nearby untagged ground was culled");
            require(!view.puddle(ground,32,-8,-80,-8,1),"Camera height ignored by puddle radius");
            require(!view.puddle(ground,32,80,-1,80,1),"Far ground kept the puddle pass");
            require(view.puddle(ReflectionReceivers.UNKNOWN,32,1000,1000,1000,1),"Unknown geometry must fail open");
            require(!view.puddle(ground,0,0,0,0,1),"Zero radius retained wet ground");
            MemoryUtil.memPutFloat(packet,Float.NaN);view.set(packet);require(view.visible(summary,1<<1,1e9,0,1e9),"Invalid view did not fail open");require(!view.visible(summary,1<<5,0,0,-8),"Disabled group retained");require(rejected>1000,"Culling never rejects anything");
            var layer=new OwnQuads.Layer();float[] uv={0,0,1,0,1,1,0,1};int[] colors={-1,-1,-1,-1},light={0xf000f0,0xf000f0,0xf000f0,0xf000f0};
            for(int i=0;i<3;i++){layer.surfaceMaterial=i==1?names.indexOf("gold"):0;float y=i;layer.quad(new float[]{0,y,0,1,y,0,1,y,1,0,y,1},colors,uv,light);}
            var actual=layer.writeSurfaceMaterials(metadata);require(actual.groups()==1<<1,"Ordered mesher lost receiver group");require((actual.lo()>>>8&255)-16==1&&(actual.hi()>>>8&255)-16==1,"Receiver bounds include the wrong quad");
            layer.reset();layer.surfaceMaterial=0;layer.quad(new float[]{0,0,0,1,0,0,1,0,1,0,0,1},colors,uv,light);require(layer.writeSurfaceMaterials(metadata).groups()==0,"Rebuild leaked a former receiver");
            var legacy=new OwnTerrain.Layer(0,1,new int[0],-1);require(legacy.receivers()==ReflectionReceivers.UNKNOWN,"Legacy mesh path must stay conservative");layer.reset();
            System.out.println("PASS: 65-profile shader/CPU receiver contract, 26 candidates, live groups, 3000 independent view cases, bobbing, unknown bounds/view, ordered QREC metadata and receiver removal on rebuild, full-geometry puddle bounds and 3D radius/height/off gates");
        }finally{MemoryUtil.nmemFree(packet);MemoryUtil.nmemFree(metadata);}
    }
}
