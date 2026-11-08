package siliconshaders.metal.sun;

import java.nio.file.Files;

/** SunWeather's per-frame values, their packet slots and settings (no game). */
public final class SunWeatherChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static boolean near(float a,float b){return Math.abs(a-b)<1e-5f;}

    public static void main(String[] args) throws Exception {
        var dir=Files.createTempDirectory("sun-weather-");
        try {
            var settings=new SunTuning(dir);float[] t=settings.values();
            // The slots are spares: never in the menu or the file, inside the 76 values the lighting packet carries.
            for(int slot:new int[]{SunWeather.THUNDER_SLOT,SunWeather.FLASH_SLOT}) {
                var param=SunTuning.PARAMS.get(slot);
                require(slot>=0&&slot<SunTuning.LIGHTING_COUNT&&param.min()==0&&param.max()==0&&t[slot]==0,"Weather slot "+slot+" is not a free packet spare");
            }
            for(int i:new int[]{SunWeather.WEATHER,SunWeather.SUN,SunWeather.MOON,SunWeather.THUNDER,SunWeather.FOG,SunWeather.FLASH})
                require(i>=0&&SunTuning.PARAMS.get(i).group()==SunTuning.Group.SKY&&i<SunTuning.MATERIAL_START,"Weather setting missing or misplaced: "+i);
            require(SunTuning.LIGHTING_COUNT==76&&SunTuning.WATER_START==76&&SunTuning.MATERIAL_COUNT==25&&SunTuning.index("materialReflections")==SunTuning.MATERIAL_START+14,
                "Packet layout moved");

            // Clear weather: everything as before.
            var clear=SunWeather.frame(0,0,0,0,t);
            require(clear.equals(SunWeather.Frame.CLEAR),"Clear weather changes the light: "+clear);
            // Full rain: the sun stays on at its share; never the old zero.
            var rain=SunWeather.frame(1,0,0,.5f,t);
            require(near(rain.light(),.38f)&&near(rain.moonLight(),.55f)&&rain.rain()==1&&rain.cover()==1&&rain.darken()==0&&near(rain.fog(),.75f)&&rain.flash()==0,"Full rain: "+rain);
            for(float r=0;r<=1;r+=.05f){var f=SunWeather.frame(r,1,0,0,t);require(f.light()>.2f&&f.light()<=1&&f.moonLight()>.3f&&f.moonLight()<=1,"Light reaches zero in weather "+r);}
            // The layer closes ahead of the rain, and light follows its depth, not a straight fade.
            var half=SunWeather.frame(.5f,0,0,0,t);
            require(near(half.rain(),.5f)&&near(half.cover(),.75f)&&near(half.light(),(float)Math.pow(.38f,.75f)),"Cover and transmittance: "+half);
            float last=1;for(float r=0;r<=1;r+=.05f){float now=SunWeather.frame(r,0,0,0,t).light();require(now<=last+1e-6f,"Light rises with the rain");last=now;}
            // A disc in the sky keeps the square of its light's share at full cover (sun at noon, moon at midnight); clear untouched.
            require(near(SunWeather.celestial(0,0,true,.38f,.55f),.38f*.38f)&&near(SunWeather.celestial(0,(float)Math.PI,true,.38f,.55f),.55f*.55f)&&SunWeather.celestial(1,(float)Math.PI,true,.38f,.55f)==1,"Sun and moon in the rainy sky");
            require(SunWeather.celestial(0,(float)Math.PI,false,.38f,.55f)==0,"Weather off changed the sky");
            // Thunder: darker, only while it rains; a strike flashes and fades over its two ticks.
            var storm=SunWeather.frame(1,1,0,0,t);
            require(near(storm.darken(),.4f)&&near(storm.light(),.38f*.6f)&&near(storm.moonLight(),.55f*.6f)&&near(storm.fog(),.75f*1.4f),"Thunderstorm: "+storm);
            require(SunWeather.frame(0,1,0,0,t).darken()==0,"Thunder without rain darkens");
            // Water: unchanged when clear, greyer in rain, darker in a storm, never black.
            require(SunWeather.waterDim(rain,0)==0&&near(SunWeather.waterDim(rain,1),SunWeather.waterDim(rain))&&near(SunWeather.waterDim(rain,2),2*.55f*.62f)&&SunWeather.waterDim(storm,3)==1&&t[SunWeather.WATER]==1,"Water strength setting");
            require(SunWeather.waterDim(clear)==0&&near(SunWeather.waterDim(rain),.55f*.62f)&&near(SunWeather.waterDim(storm),.55f*(1-.228f))&&SunWeather.waterDim(storm)<1,"Water dimming");
            require(near(SunWeather.frame(1,1,2,0,t).flash(),1)&&near(SunWeather.frame(1,1,1,.5f,t).flash(),.0625f)&&SunWeather.frame(1,1,0,0,t).flash()==0,"Flash timing");
            // Wetness: builds up over a 30 s half life, dries over 10 s, never outside rainy biomes; packed only when on.
            var wetness=new SunWeather.Wetness();long now=1_000_000_000L;wetness.update(now,false,1,true);
            for(int i=0;i<120;i++)wetness.update(now+=250_000_000L,false,1,true);   // 30 s of rain
            require(Math.abs(wetness.value()-.5f)<.02f,"Wetting half life: "+wetness.value());
            for(int i=0;i<40;i++)wetness.update(now+=250_000_000L,false,0,true);    // 10 s dry
            require(Math.abs(wetness.value()-.25f)<.02f,"Drying half life: "+wetness.value());
            float held=wetness.value();for(int i=0;i<40;i++)wetness.update(now+=250_000_000L,true,1,true);
            require(wetness.value()==held,"Wetness changed while paused");
            // The drying setting: 120 s by default keeps the world wet far longer than Iris's 10 s.
            var slow=new SunWeather.Wetness();long at=1_000_000_000L;slow.update(at,false,1,true,120);
            for(int i=0;i<400;i++)slow.update(at+=250_000_000L,false,1,true,120);
            float wetAtStop=slow.value();for(int i=0;i<480;i++)slow.update(at+=250_000_000L,false,0,true,t[SunWeather.WET_DRYING]);   // 120 s dry
            require(t[SunWeather.WET_DRYING]==120&&Math.abs(slow.value()-wetAtStop*.5f)<.02f,"Drying setting half life: "+slow.value());
            var desert=new SunWeather.Wetness();for(int i=0;i<200;i++)desert.update(1_000_000_000L+i*250_000_000L,false,1,false);
            require(desert.value()==0,"Desert got wet");
            float[] wetCopy=t.clone();SunWeather.packWet(wetCopy,.6f);
            require(near(wetCopy[SunWeather.WET_SLOT],.6f)&&near(wetCopy[SunWeather.PUDDLE_SLOT],.5f),"Wet packing");
            wetCopy[SunWeather.WET]=0;SunWeather.packWet(wetCopy,.6f);require(wetCopy[SunWeather.WET_SLOT]==0,"Wet surfaces off still packed");
            for(int slot:new int[]{SunWeather.WET_SLOT,SunWeather.PUDDLE_SLOT}){var param=SunTuning.PARAMS.get(slot);require(slot<SunTuning.LIGHTING_COUNT&&param.min()==0&&param.max()==0,"Wet slot is not a spare");}
            // Broken level values stay harmless.
            var broken=SunWeather.frame(Float.NaN,Float.POSITIVE_INFINITY,0,0,t);
            require(broken.equals(SunWeather.Frame.CLEAR),"Non-finite weather: "+broken);
            // Settings: off is clear weather; the sliders change it and persist.
            settings.set(SunWeather.WEATHER,0);
            require(SunWeather.frame(1,1,2,0,settings.values()).equals(SunWeather.Frame.CLEAR),"Weather off still changes the light");
            settings.set(SunWeather.WEATHER,1);settings.set(SunWeather.SUN,.5f);settings.set(SunWeather.FOG,2);settings.set(SunWeather.FLASH,0);
            float[] loaded=new SunTuning(dir).values();
            var tuned=SunWeather.frame(1,0,2,0,loaded);
            require(near(tuned.light(),.5f)&&near(tuned.fog(),2)&&tuned.flash()==0,"Weather settings did not persist: "+tuned);
            // Packed into the shader's copy only.
            float[] copy=loaded.clone();SunWeather.pack(copy,storm);
            require(copy[SunWeather.THUNDER_SLOT]==storm.darken()&&copy[SunWeather.FLASH_SLOT]==storm.flash(),"Pack slots");
            // Folded per-pixel work: denser haze, only a part of it as reach; thunder-darkened ambient sky light; nothing else of the copy changed.
            require(near(copy[SunWeather.FOG_DENSITY],loaded[SunWeather.FOG_DENSITY]*(1+storm.fog()))&&near(copy[SunWeather.FOG_HEIGHT],loaded[SunWeather.FOG_HEIGHT]/(1+SunWeather.HAZE_REACH*storm.fog())),"Haze fold");
            for(int i:SunWeather.SKY_LIGHT)require(near(copy[i],loaded[i]*(1-storm.darken())),"Sky light fold");
            int changed=0;for(int i=0;i<copy.length;i++)if(copy[i]!=loaded[i])changed++;
            require(changed==6,"Pack touched "+changed+" values");  // thunder slot, haze 2, sky light 3 (no flash in this frame)
            float[] clearCopy=loaded.clone();SunWeather.pack(clearCopy,SunWeather.Frame.CLEAR);require(java.util.Arrays.equals(clearCopy,loaded),"Clear weather changes the packet");
            require(new SunTuning(dir).value(SunWeather.THUNDER_SLOT)==0,"Per-frame weather leaked into the settings");
            settings.reset(SunTuning.Group.SKY);
            require(settings.value(SunWeather.SUN)==.38f&&settings.value(SunWeather.WEATHER)==1,"Sky reset skips weather");
            System.out.printf("PASS: clear = before, full rain sun %.2f (was 0), storm %.3f, flash fade, spare slots, settings and reset%n",rain.light(),storm.light());
        } finally { try(var files=Files.walk(dir)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
}
