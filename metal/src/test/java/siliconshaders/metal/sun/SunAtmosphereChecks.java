package siliconshaders.metal.sun;

import java.nio.file.Files;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** When SunAtmosphere shows auroras, the night nebula and rainbows, and the packet values it uses (no game). */
public final class SunAtmosphereChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        var dir=Files.createTempDirectory("sun-atmosphere-");
        try {
            var settings=new SunTuning(dir);float[] t=settings.values();
            // Night, clear, full moon in a cold biome: Complementary's default aurora; never by day or in rain.
            var night=SunAtmosphere.frame(t,-.8f,0,0,false,true,true);
            require(night.aurora()==1&&night.nebula()==1&&night.rainbow()==0,"Clear cold full-moon night: "+night);
            require(SunAtmosphere.frame(t,-.8f,0,0,false,false,true).aurora()==0&&SunAtmosphere.frame(t,-.8f,0,0,false,true,false).aurora()==0,"Default aurora outside its condition");
            // The stars: every clear night, whatever the moon or biome; never by day, in rain or in the storm that needs rain.
            require(SunAtmosphere.frame(t,-.8f,0,0,false,false,false).stars()==1,"No stars on a clear night");
            require(SunAtmosphere.frame(t,.5f,0,0,false,false,false).stars()==0&&SunAtmosphere.frame(t,-.8f,1,1,true,false,false).stars()==0,"Stars by day or in rain");
            float half=SunAtmosphere.frame(t,-.8f,.5f,0,true,false,false).stars();require(half>.4f&&half<.6f,"Stars do not fade with the rain: "+half);
            float dusk=SunAtmosphere.frame(t,-.1f,0,0,false,false,false).stars();require(dusk>0&&dusk<1,"Stars do not fade in at dusk: "+dusk);
            float[] noStars=t.clone();noStars[SunAtmosphere.NIGHT_STARS]=0;require(SunAtmosphere.frame(noStars,-.8f,0,0,false,false,false).stars()==0,"Stars at 0");
            noStars[SunAtmosphere.NIGHT_STARS]=2;require(SunAtmosphere.frame(noStars,-.8f,0,0,false,false,false).stars()==2,"Star setting ignored");
            var day=SunAtmosphere.frame(t,.5f,0,0,false,true,true);require(day.aurora()==0&&day.nebula()==0&&day.rainbow()==0,"Night sky by day");
            var rainy=SunAtmosphere.frame(t,-.8f,1,1,true,true,true);require(rainy.aurora()==0&&rainy.nebula()==0&&rainy.rainbow()==0,"Night sky in rain");
            float[] modes=t.clone();modes[SunAtmosphere.AURORA]=2;require(SunAtmosphere.frame(modes,-.8f,0,0,false,true,false).aurora()==1,"Mode 2: cold biome without full moon");
            modes[SunAtmosphere.AURORA]=3;require(SunAtmosphere.frame(modes,-.8f,0,0,false,false,false).aurora()==1,"Mode 3: every clear night");
            modes[SunAtmosphere.AURORA_BLOCKY]=1;require(SunAtmosphere.frame(modes,-.8f,0,0,false,false,false).aurora()==-1,"Blocky style is not negative");modes[SunAtmosphere.AURORA_BLOCKY]=0;
            modes[SunAtmosphere.AURORA]=0;require(SunAtmosphere.frame(modes,-.8f,0,0,false,true,true).aurora()==0,"Aurora off");
            // Rainbows: after rain (wet, no rain now), a low or mid-height sun, rainy biomes; none at noon, in rain or when dry.
            var bow=SunAtmosphere.frame(t,.35f,0,1,true,false,false);require(bow.rainbow()>.3f&&bow.aurora()==0&&bow.nebula()==0,"After-rain bow: "+bow);
            require(SunAtmosphere.frame(t,.95f,0,1,true,false,false).rainbow()==0,"Rainbow at noon");
            require(SunAtmosphere.frame(t,.35f,1,1,true,false,false).rainbow()==0&&SunAtmosphere.frame(t,.35f,0,.2f,true,false,false).rainbow()==0,"Rainbow in rain or dry");
            require(SunAtmosphere.frame(t,.35f,0,1,false,false,false).rainbow()==0,"Rainbow in a desert");
            float[] noBows=t.clone();noBows[SunAtmosphere.RAINBOWS]=0;require(SunAtmosphere.frame(noBows,.35f,0,1,true,false,false).rainbow()==0,"Rainbows off");
            float[] always=t.clone();always[SunAtmosphere.RAINBOWS]=2;
            require(SunAtmosphere.frame(always,.35f,0,0,false,false,false).rainbow()>.3f&&SunAtmosphere.frame(always,.35f,1,0,false,false,false).rainbow()==0,"Mode 2: dry desert bow, none in rain");
            float[] strong=t.clone();strong[SunAtmosphere.RAINBOW_STRENGTH]=2;strong[SunAtmosphere.RAINBOW_SIZE]=1.5f;var sb=SunAtmosphere.frame(strong,.35f,0,1,true,false,false);
            require(Math.abs(sb.rainbow()-2*bow.rainbow())<1e-5f&&sb.diameter()==1.5f,"Rainbow strength or size");
            var broken=SunAtmosphere.frame(t,Float.NaN,0,0,true,true,true);require(broken.aurora()==0&&broken.nebula()==0&&broken.rainbow()==0,"Non-finite input");
            // The sky clock: game seconds, wrapping after CLOCK_PERIOD; negative game times stay in range.
            require(SunAtmosphere.clock(400,0)==20&&SunAtmosphere.clock(400,.5f)==20.025f&&SunAtmosphere.clock((long)SunAtmosphere.CLOCK_PERIOD*20+40,0)==2&&SunAtmosphere.clock(-20,0)>0,"Sky clock");
            // The packet values: read by no shader (only the CPU's moon brightness), written into the GPU copy only.
            for(int slot:new int[]{SunAtmosphere.AURORA_SLOT,SunAtmosphere.NEBULA_SLOT,SunAtmosphere.RAINBOW_SLOT,SunAtmosphere.CLOCK_SLOT,SunAtmosphere.BLUR_SLOT})require(slot>=0&&slot<SunTuning.LIGHTING_COUNT,"Slot outside the packet");
            try(MemoryStack stack=MemoryStack.stackPush()){
                long copy=stack.nmalloc(16,t.length*4);for(int i=0;i<t.length;i++)MemoryUtil.memPutFloat(copy+4L*i,t[i]);
                SunAtmosphere.write(copy,new SunAtmosphere.Frame(.5f,.25f,0,1.5f,0),12);
                require(MemoryUtil.memGetFloat(copy+4L*SunAtmosphere.AURORA_SLOT)==.5f&&MemoryUtil.memGetFloat(copy+4L*SunAtmosphere.NEBULA_SLOT)==.25f&&MemoryUtil.memGetFloat(copy+4L*SunAtmosphere.CLOCK_SLOT)==12,"Packet write at night");
                SunAtmosphere.write(copy,new SunAtmosphere.Frame(0,0,.75f,1.5f,0),12,.4f,1.5f);
                require(MemoryUtil.memGetFloat(copy+4L*SunAtmosphere.BLUR_SLOT)==60.4f&&t[SunAtmosphere.BLUR_SLOT]==settings.value(SunAtmosphere.BLUR_SLOT),"Depth and blur written to the GPU copy only");
                // The shader's decoding (sun.metal): depth = floor(v / 2) / 20, softness = v - 2 floor(v / 2), for every setting.
                for(float d=.5f;d<=2.001f;d+=.05f)for(float b=0;b<=1.001f;b+=.05f){
                    float v=SunAtmosphere.packAurora(b,d),code=(float)Math.floor(v*.5f);
                    require(Math.abs(code/20-d)<1e-4f&&Math.abs((v-code*2)-Math.min(1,b))<1e-4f,"Aurora packing at depth "+d+" blur "+b);
                }
                require(MemoryUtil.memGetFloat(copy+4L*SunAtmosphere.NEBULA_SLOT)==-1.5f&&MemoryUtil.memGetFloat(copy+4L*SunAtmosphere.RAINBOW_SLOT)==.75f,"Packet write by day (minus the diameter)");
                require(t[SunAtmosphere.AURORA_SLOT]==settings.value(SunAtmosphere.AURORA_SLOT),"The CPU's own moon values changed");
            }
            // Curvature uses reserved components in the existing 48-byte aurora extension.
            try(MemoryStack stack=MemoryStack.stackPush()) {
                long packet=stack.nmalloc(16,SunAurora.FLOATS*4);
                SunAurora.write(packet,t);
                require(Math.abs(MemoryUtil.memGetFloat(packet+4)-.08f)<1e-6f&&Math.abs(MemoryUtil.memGetFloat(packet+20)-(float)Math.sqrt(2))<1e-6f,"Default curvature/extent coefficients");
                require(MemoryUtil.memGetFloat(packet+32)==16,"Curvature changed the layer budget");
                require(MemoryUtil.memGetFloat(packet+28)==.6f,"Default aurora count coefficient");
                require(Math.abs(MemoryUtil.memGetFloat(packet+48+4)-1)<1e-6f&&MemoryUtil.memGetFloat(packet+48+12)==0&&MemoryUtil.memGetFloat(packet+64+12)==0&&MemoryUtil.memGetFloat(packet+80+12)==1&&MemoryUtil.memGetFloat(packet+96+12)==1,"Sky colors, nebula size and speed");
                settings.set(SunAurora.COUNT,3);SunAurora.write(packet,settings.values());
                require(MemoryUtil.memGetFloat(packet+28)==.3f&&MemoryUtil.memGetFloat(packet+32)==16,"Aurora count changes the layer budget or fails to reach the packet");
                float[] flat=t.clone();flat[SunAurora.CURVED]=0;SunAurora.write(packet,flat);
                require(MemoryUtil.memGetFloat(packet+4)==0,"Curvature switch does not bypass the projection");
                flat[SunAurora.CURVED]=1;flat[SunAurora.CURVATURE]=0;SunAurora.write(packet,flat);
                require(MemoryUtil.memGetFloat(packet+4)==0,"Zero strength is not flat");
                settings.set(SunAurora.CURVATURE,1.5f);settings.set(SunAurora.EXTENT,60);settings.set(SunAurora.CURVED,0);
                var loaded=new SunTuning(dir);
                require(loaded.value(SunAurora.CURVATURE)==1.5f&&loaded.value(SunAurora.EXTENT)==60&&loaded.value(SunAurora.CURVED)==0&&loaded.value(SunAurora.COUNT)==3,"Curvature/count settings did not persist");
                settings.reset(SunTuning.Group.SKY);SunAurora.write(packet,settings.values());
                require(Math.abs(MemoryUtil.memGetFloat(packet+4)-.08f)<1e-6f&&MemoryUtil.memGetFloat(packet+28)==.6f,"Sky reset did not restore curvature/amount");
            }
            for(int i:new int[]{SunAurora.COUNT,SunAurora.CURVED,SunAurora.CURVATURE,SunAurora.EXTENT,SunAtmosphere.AURORA,SunAtmosphere.AURORA_STRENGTH,SunAtmosphere.NEBULA,SunAtmosphere.RAINBOWS,SunAtmosphere.RAINBOW_STRENGTH,SunAtmosphere.RAINBOW_SIZE})
                require(i>=0&&SunTuning.PARAMS.get(i).group()==SunTuning.Group.SKY&&i<SunTuning.MATERIAL_START,"Setting "+i);
            System.out.println("PASS: aurora conditions and modes, night nebula, after-rain rainbows, rain/day/desert exclusions, packet slots");
        } finally { try(var files=Files.walk(dir)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
}
