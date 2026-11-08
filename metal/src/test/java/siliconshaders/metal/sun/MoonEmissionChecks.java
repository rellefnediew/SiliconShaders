package siliconshaders.metal.sun;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.system.*;

/** Finite sources against geometric/energy invariants, plus real Java packets for the native GPU integration test. */
public final class MoonEmissionChecks {
    private static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("moon-emission-");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            float[] base=new SunTuning(dir).values();base[SunCelestial.STYLE]=1;
            long p=stack.nmalloc(16,SunCelestial.FLOATS*4);ByteBuffer fixtures=ByteBuffer.allocate(8+192*(12+224)).order(ByteOrder.LITTLE_ENDIAN);
            fixtures.putInt(0x4d454d31).putInt(192);int checked=0;
            for(int shape=0;shape<3;shape++)for(int phase=0;phase<8;phase++)for(int angle=0;angle<360;angle+=45) {
                float[] v=base.clone();v[SunCelestial.MOON_SHAPE]=shape;v[SunCelestial.PHASE_ANGLE]=angle;
                SunCelestial.write(p,v,phase);float[] packed=new float[56];for(int i=0;i<56;i++)packed[i]=MemoryUtil.memGetFloat(p+4L*i);
                double total=0;for(int i=0;i<8;i++) {
                    float x=packed[24+i*4],y=packed[25+i*4],weight=packed[26+i*4];total+=weight;
                    require(weight>=0&&Float.isFinite(x+y+weight),"Invalid source");if(weight==0)continue;
                    double metric=shape==0?Math.hypot(x,y):shape==1?Math.max(Math.abs(x),Math.abs(y)):Math.abs(x)+Math.abs(y);
                    require(metric<=1.0001,"Emitter outside the visible silhouette");
                    if(shape==0&&phase==2) {
                        double projection=x*Math.cos(Math.toRadians(angle))+y*Math.sin(Math.toRadians(angle));
                        require(projection>-.12,"Half-moon source lies on its unlit side");
                    }
                }
                require(Math.abs(total-packed[22])<1e-5,"Source energy differs from visible fraction");
                if(phase==0)require(Math.abs(total-1)<1e-5,"Full moon lost energy");
                if(phase==4)require(total==0&&packed[23]==0,"New moon still emits");
                if(phase==2||phase==6)require(Math.abs(total-.5)<.015,"Half-moon flux");
                if(shape==0)require(Math.abs(total-(.5+.5*Math.cos(phase*Math.PI/4)))<.02,"Circular phase flux vs independent area oracle");
                if(phase==2&&angle==0)require(packed[20]>.3,"Half-moon source did not move right");
                if(phase==2&&angle==90)require(packed[21]>.3,"Rotated half-moon source did not move up");
                fixtures.putInt(shape).putInt(phase).putInt(angle);for(float value:packed)fixtures.putFloat(value);checked++;
            }
            float[] always=base.clone();always[SunCelestial.MOON_PHASES]=0;SunCelestial.write(p,always,4);require(MemoryUtil.memGetFloat(p+88)==1,"Always-full visual moon does not emit at world new moon");
            float[] offset=base.clone();offset[SunCelestial.PHASE_OFFSET]=180;SunCelestial.write(p,offset,0);require(MemoryUtil.memGetFloat(p+88)==0,"Visual phase offset did not extinguish the source");
            if(args.length>0){Path out=Path.of(args[0]);Files.createDirectories(out.getParent());Files.write(out,fixtures.array());}
            System.out.println("PASS: "+checked+" Java emitter fixtures, bounded lit source positions, full/half/new energy, independent circular area oracle, source rotation, visual phase override and offset");
        }finally{try(var files=Files.walk(dir)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
}
