package siliconshaders.metal.settings;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

/** User contracts: portable codes, preserved preferences, profile library recovery and menu transaction/undo. */
public final class GraphicsMenuChecks {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static String rawCode(String raw)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();try(DeflaterOutputStream zipped=new DeflaterOutputStream(output)){zipped.write(raw.getBytes(StandardCharsets.UTF_8));}
        byte[] bytes=output.toByteArray();CRC32 crc=new CRC32();crc.update(bytes);return "SILICONSHADERS1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+String.format(Locale.ROOT,"%08x",crc.getValue());
    }
    public static void main(String[] args)throws Exception{
        // Without value comparison no two prepared render types merge: thousands of draws for a few items (docs/performance/render-type-merge.md).
        try(var in=GraphicsMenuChecks.class.getResourceAsStream("/siliconshaders-pack.mixins.json")){require(in!=null&&new String(in.readAllBytes(),StandardCharsets.UTF_8).contains("\"ScissorStateValueMixin\""),"The render type merge fix is not registered");}
        try(var in=GraphicsMenuChecks.class.getResourceAsStream("/siliconshaders-pack.mixins.json")){String mixins=new String(in.readAllBytes(),StandardCharsets.UTF_8);require(mixins.contains("\"ShaderKeyOptionsMixin\"")&&mixins.contains("\"ShaderKeyMixin\""),"The shader key is not registered");}
        Path directory=Files.createTempDirectory("siliconshaders-menu-check");
        try{
            GraphicsSettings settings=new GraphicsSettings(directory);Map<String,Float> initial=settings.capture();
            require(initial.keySet().equals(GraphicsCatalog.RULES.keySet()),"Visible schema differs from captured preferences");
            ProfileStore store=new ProfileStore(directory);MenuSession menu=new MenuSession(settings,"");
            menu.checkpoint();menu.set("camera.godrays",1);menu.set("camera.raysStrength",.57f);menu.set("shadow.size",2048);menu.set("sun.endRingTransparency",.57f);menu.set("sun.endFogStrength",.65f);menu.set("sun.endFogDistance",192);
            require(settings.capture().get("camera.godrays")==1&&!Files.exists(directory.resolve("config")),"Live preview wrote a preference file");
            GraphicsProfile all=menu.snapshot(UUID.randomUUID().toString(),"Licht überm See ☀",GraphicsProfile.Scope.ALL);
            String encoded=ProfileCodec.encode(all);GraphicsProfile decoded=ProfileCodec.decode(encoded);
            require(decoded.name().equals(all.name())&&decoded.values().equals(all.values())&&!decoded.id().equals(all.id()),"Portable profile changed its name/values or reused local identity");
            require(ProfileCodec.encode(decoded).equals(encoded),"Clipboard code is not stable across local identities");
            require(ProfileCodec.decode(" \n"+encoded+"\n").values().equals(all.values()),"Pasted surrounding whitespace rejected");
            String changed=encoded.substring(0,encoded.length()-1)+(encoded.endsWith("0")?"1":"0");rejects(()->ProfileCodec.decode(changed),"Changed checksum accepted");
            rejects(()->ProfileCodec.decode(encoded.replace("SILICONSHADERS1:","SILICONSHADERS2:")),"Future codec version accepted");
            rejects(()->ProfileCodec.decode(encoded.substring(0,encoded.length()/2)),"Truncated clipboard accepted");
            rejects(()->ProfileCodec.decode("x".repeat(ProfileCodec.MAX_CODE+1)),"Oversized code accepted");
            String bomb=rawCode(" ".repeat(ProfileCodec.MAX_JSON+1));rejects(()->ProfileCodec.decode(bomb),"Oversized decoded data accepted");
            String nested=rawCode("[".repeat(9000)+"0"+"]".repeat(9000));rejects(()->ProfileCodec.decode(nested),"Recursive JSON reached the parser");
            String badType=rawCode("{\"version\":1,\"name\":\"Test\",\"scope\":\"ALL\",\"values\":{\"camera.bloom\":\"NaN\"}}");rejects(()->ProfileCodec.decode(badType),"Non-numeric profile field accepted");
            String fractional=rawCode("{\"version\":1.5,\"name\":\"Test\",\"scope\":\"ALL\",\"values\":{\"camera.bloom\":1}}");rejects(()->ProfileCodec.decode(fractional),"Fractional format version accepted");
            GraphicsProfile look=menu.snapshot(UUID.randomUUID().toString(),"Nur Farben",GraphicsProfile.Scope.LOOK);
            require(!look.values().containsKey("shadow.size")&&!look.values().containsKey("camera.raysSamples")&&!look.values().containsKey("sun.auroraQuality"),"Look-only export changed quality controls");
            require(!all.values().containsKey("shadow.gbufferView")&&!all.values().containsKey("camera.raysVariant"),"Sharing exports internal implementation choices");
            Map<String,Float> recipient=new LinkedHashMap<>(initial);recipient.put("shadow.size",4096f);recipient.put("camera.raysSamples",48f);
            Map<String,Float> imported=GraphicsCatalog.merge(recipient,look);require(imported.get("shadow.size")==4096&&imported.get("camera.raysSamples")==48&&imported.get("camera.godrays")==1,"Look import changed recipient performance or lost effect state");
            // A code names what it sets; this version's other settings are not its business.
            GraphicsProfile future=new GraphicsProfile(UUID.randomUUID().toString(),"Future",GraphicsProfile.Scope.ALL,Map.of("camera.godrays",1f,"future.option",5f));
            require(GraphicsCatalog.fit(future).skipped()==1&&GraphicsCatalog.merge(initial,future).get("camera.godrays")==1,"Unknown names of a newer version were not skipped");
            require(GraphicsCatalog.fit(all).equals(new GraphicsCatalog.Fit(0,0,0))&&GraphicsCatalog.fit(look).equals(new GraphicsCatalog.Fit(0,0,0)),"A code of this version warns about itself");
            // Old codes work for good. This file was made by dim.17: never regenerate it. It proves the format, the names and the values still read.
            String frozen;try(var in=GraphicsMenuChecks.class.getResourceAsStream("/siliconshaders/profile-code-v1.txt")){require(in!=null,"Frozen code not packaged");frozen=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
            GraphicsProfile oldCode=ProfileCodec.decode(frozen);
            // A setting removed on purpose goes here; a renamed one goes into GraphicsCatalog.RENAMED.
            Set<String> retired=Set.of();
            for(String key:oldCode.values().keySet())require(GraphicsCatalog.rule(key)!=null||retired.contains(key),"Old codes lose this setting: "+key);
            Map<String,Float> restored=GraphicsCatalog.merge(initial,oldCode);
            require(restored.get("camera.godrays")==1&&Math.abs(restored.get("camera.raysStrength")-.57f)<.001f&&restored.get("shadow.size")==2048&&Math.abs(restored.get("sun.endFogStrength")-.65f)<.001f&&restored.get("sun.endFogDistance")==192,"Frozen code no longer means the same");
            // A feature newer than the code: left out of a complete code here, it keeps what the player has. Its colour warns once.
            Map<String,Float> without=new TreeMap<>(all.values());without.remove("sun.endFogStrength");for(char c:"RGB".toCharArray())without.remove("sun.endFogColor"+c);
            GraphicsProfile older=new GraphicsProfile(UUID.randomUUID().toString(),"Alt",GraphicsProfile.Scope.ALL,without);
            Map<String,Float> player=new LinkedHashMap<>(initial);player.put("sun.endFogStrength",1.7f);player.put("sun.endFogColorR",.1f);
            Map<String,Float> kept=GraphicsCatalog.merge(player,older);
            require(kept.get("sun.endFogStrength")==1.7f&&kept.get("sun.endFogColorR")==.1f&&kept.get("camera.godrays")==1,"An old code changed a setting newer than itself");
            require(GraphicsCatalog.fit(older).equals(new GraphicsCatalog.Fit(2,0,0)),"Wrong count of settings an old code leaves alone: "+GraphicsCatalog.fit(older));
            // The first format, a handful of settings: still a working code, and a picture code never warns about quality settings.
            GraphicsProfile first=ProfileCodec.decode(rawCode("{\"version\":1,\"name\":\"Alt\",\"scope\":\"LOOK\",\"values\":{\"camera.godrays\":1}}").replace("SILICONSHADERS1:","MCOPT1:"));
            int inLook=0;for(var rule:GraphicsCatalog.RULES.values())if(rule.shareable()&&!rule.quality()&&(GraphicsCatalog.colorStem(rule.key())==null||rule.key().endsWith("R")))inLook++;
            require(GraphicsCatalog.merge(recipient,first).get("shadow.size")==4096&&GraphicsCatalog.fit(first).unchanged()==inLook-1,"First-format code failed");
            // Ranges may change: a value beyond today's range moves to the closest one allowed instead of costing the whole code.
            GraphicsProfile stretched=new GraphicsProfile(UUID.randomUUID().toString(),"Weit",GraphicsProfile.Scope.ALL,Map.of("camera.bloomStrength",100f,"shadow.size",3000f));
            Map<String,Float> fitted=GraphicsCatalog.merge(initial,stretched);GraphicsCatalog.validate(fitted);
            require(fitted.get("camera.bloomStrength")==GraphicsCatalog.RULES.get("camera.bloomStrength").max()&&fitted.get("shadow.size")==2048&&GraphicsCatalog.fit(stretched).adjusted()==2,"Out-of-range value not fitted");
            menu.matches(stretched);
            // Nothing this version can use: rejected, and the live settings stay as they were.
            GraphicsProfile useless=new GraphicsProfile(UUID.randomUUID().toString(),"Bad",GraphicsProfile.Scope.ALL,Map.of("future.option",5f));
            Map<String,Float> before=settings.capture();rejects(()->menu.replace(useless),"Profile without a usable setting applied");require(settings.capture().equals(before),"Rejected import mutated live settings");
            store.put(all);store.put(look);require(new ProfileStore(directory).profiles().size()==2,"Local profiles did not persist");
            store.put(all.renamed("Mein Nachtlicht"));require(store.profiles().size()==2&&new ProfileStore(directory).find(all.id()).name().equals("Mein Nachtlicht"),"Rename duplicated the profile or did not persist");
            menu.replace(decoded);menu.finish(store);Map<String,Float> saved=new GraphicsSettings(directory).capture();
            require(saved.equals(settings.capture()),"Done did not persist all four legacy stores");require(new ProfileStore(directory).active().equals(decoded.id()),"Active profile selection was not committed");
            byte[] disk=Files.readAllBytes(directory.resolve("config/siliconshaders-camera.json"));MenuSession edit=new MenuSession(settings,all.id());edit.checkpoint();edit.set("camera.bloom",1);edit.set("look.exposure",1.75f);
            require(Arrays.equals(disk,Files.readAllBytes(directory.resolve("config/siliconshaders-camera.json"))),"Slider preview rewrote the settings file");
            edit.undo();require(settings.capture().equals(saved),"Undo did not restore the complete preview state");
            edit.checkpoint();edit.set("camera.bloom",1);edit.abandon();require(settings.capture().equals(saved),"Cancel did not restore the opening settings");
            // Simulated failure during the second file replacement, after the first destination has changed.
            Path a=directory.resolve("a.json"),b=directory.resolve("b.json");Files.writeString(a,"old A");Files.writeString(b,"old B");int[] moves={0};
            Map<Path,String> documents=new LinkedHashMap<>();documents.put(a,"new A");documents.put(b,"new B");boolean failed=false;
            try{SettingsFiles.write(documents,(from,to)->{if(++moves[0]==2)throw new IOException("injected replacement failure");Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);});}catch(IOException expected){failed=true;}
            require(failed&&Files.readString(a).equals("old A")&&Files.readString(b).equals("old B"),"Partial preferences transaction was not rolled back");
            store.delete(look.id());require(new ProfileStore(directory).find(look.id())==null,"Deleted profile returned on reload");
            Path library=directory.resolve("config/siliconshaders-graphics-profiles.json");String corrupt="{broken profile data";Files.writeString(library,corrupt);ProfileStore broken=new ProfileStore(directory);
            require(!broken.issue().isEmpty(),"Corrupt library was silently replaced");try{broken.put(all);throw new AssertionError("Corrupt library overwritten");}catch(IOException expected){}require(Files.readString(library).equals(corrupt),"Corrupt library content was lost");
            try(var in=GraphicsMenuChecks.class.getResourceAsStream("/assets/siliconshaders/lang/en_us.json")){require(in!=null,"Menu translations not packaged");var english=com.google.gson.JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();try(var de=GraphicsMenuChecks.class.getResourceAsStream("/assets/siliconshaders/lang/de_de.json")){var german=com.google.gson.JsonParser.parseReader(new InputStreamReader(de)).getAsJsonObject();for(String key:english.keySet())if(key.startsWith("siliconshaders.menu."))require(german.has(key),"Missing German menu label: "+key);}}
            // Menu structure: what a player sees must stay whole and reachable.
            var rules=GraphicsCatalog.RULES;
            for(String key:List.of("weatherSun","rainDim","weatherThunder","weatherMoon","weatherNightDim","weatherThunderMoon")){
                var rule=rules.get("sun."+key);
                String group=List.of("weatherSun","rainDim","weatherThunder").contains(key)?"weatherDay":"weatherNight";
                require(rule!=null&&rule.group().equals(group)&&GraphicsCatalog.feature(rule).equals("weather"),"Day/night weather setting misplaced: "+key);
                require(GraphicsCatalog.tier(rule)==GraphicsCatalog.Tier.LOOK&&GraphicsCatalog.requires(rule.key()).contains("sun.weather"),"Weather control hidden under More or active when weather is off: "+key);
            }
            var ring=rules.get("sun.endRingTransparency");
            require(ring!=null&&!ring.toggle()&&ring.min()==0&&ring.max()==1&&Math.abs(ring.step()-.01f)<1e-6,"Ring transparency is not a 0–100% slider");
            require(ring.group().equals("endSky")&&GraphicsCatalog.feature(ring).equals("endSky")&&GraphicsCatalog.tier(ring)==GraphicsCatalog.Tier.LOOK,"Ring transparency is not in the basic End sky section");
            require(GraphicsCatalog.FEATURES.stream().anyMatch(f->f.id().equals("endSky")&&f.area().equals("sky")),"End sky outside Sky area");
            require(GraphicsCatalog.requires(ring.key()).contains("sun.endPlanet")&&GraphicsCatalog.requires("sun.endPlanet").contains("sun.skyEnabled"),"Ring control active without planet/sky");
            require(Math.abs(decoded.values().get(ring.key())-.57f)<1e-6&&Math.abs(saved.get(ring.key())-.57f)<1e-6,"Ring transparency lost in portable profile or settings save");
            // The menu's icon comes from the jar itself: without Fabric API no resource pack serves it.
            try(var icon=GraphicsMenuChecks.class.getResourceAsStream("/assets/siliconshaders/textures/gui/icon.png")){
                require(icon!=null,"Menu icon missing from the mod's resources");
                byte[] head=icon.readNBytes(24);
                require(head.length==24&&head[1]=='P'&&head[2]=='N'&&head[3]=='G'&&java.nio.ByteBuffer.wrap(head,16,4).getInt()==256,"Menu icon is not the expected PNG");
            }
            require(rules.containsKey("sun.underwaterHalf")&&rules.get("sun.underwaterHalf").toggle()&&rules.get("sun.underwaterHalf").neutral()==1&&GraphicsCatalog.feature(rules.get("sun.underwaterHalf")).equals("water")&&GraphicsCatalog.tier(rules.get("sun.underwaterHalf"))==GraphicsCatalog.Tier.QUALITY,"Underwater half resolution switch");
            // One control per step count in the menu; the value stays usable by quality levels and shared profiles.
            for(String key:List.of("sun.waterSteps","sun.materialWorldReflectionSteps"))require(GraphicsCatalog.tier(rules.get(key))==GraphicsCatalog.Tier.DEVELOPER&&rules.get(key).shareable()&&rules.get(key).quality(),"Merged step count: "+key);
            require(GraphicsCatalog.feature(rules.get("sun.waterSteps")).equals("water")&&GraphicsCatalog.feature(rules.get("sun.materialWorldReflectionSteps")).equals("materials"),"Merged step counts left their feature");
            for(String key:List.of("sun.endLightElevation","sun.endLightStrength","sun.endHoleSize","sun.endDiscBrightness","sun.endStars"))require(rules.get(key).group().equals("endSky")&&GraphicsCatalog.tier(rules.get(key))==GraphicsCatalog.Tier.LOOK,"End setting placement: "+key);
            require(GraphicsCatalog.requires("sun.endHoleSize").contains("sun.endBlackHole")&&!GraphicsCatalog.requires("sun.endStars").contains("sun.endBlackHole"),"End setting dependencies");
            for(var level:GraphicsCatalog.QUALITY_LEVELS)require(!level.containsKey("sun.renderScale"),"Quality level changed the player's MetalFX choice");
            // A short first view: the fog's switch, strength, colour and height; its details under More.
            for(String key:List.of("sun.endFogStrength","sun.endFogColorR","sun.endFogHeight"))require(rules.get(key).group().equals("endSky")&&GraphicsCatalog.tier(rules.get(key))==GraphicsCatalog.Tier.LOOK&&rules.get(key).shareable(),"End fog first view: "+key);
            for(String key:List.of("sun.endFogDistance","sun.endFogBrightness","sun.endFogThickness","sun.endFogSize","sun.endFogWisps","sun.endFogSpeed","sun.endFogEmber","sun.endFogEmberColorR","sun.endFogHoleLight"))require(rules.get(key).group().equals("endSky")&&GraphicsCatalog.tier(rules.get(key))==GraphicsCatalog.Tier.MORE&&rules.get(key).shareable(),"End fog detail not under More: "+key);
            // Costs name real settings; a colour is one setting; a changed value is told from a standard one.
            for(String key:List.of("shadow.size","sun.waterReflection","sun.waterRefraction","camera.motion","camera.dof","sun.materials","sun.ssao","sun.coloredLight","sun.materialWorldReflections"))require(rules.containsKey(key)&&GraphicsCatalog.cost(key)>0,"Cost on an unknown setting: "+key);
            require(GraphicsCatalog.cost("sun.endFog")==0&&GraphicsCatalog.saves("sun.renderScale")&&rules.containsKey("sun.renderScale"),"Costs claim something unmeasured");
            require("sun.endFogColor".equals(GraphicsCatalog.colorStem("sun.endFogColorG"))&&GraphicsCatalog.colorStem("sun.endFogStrength")==null&&GraphicsCatalog.colorStem("sun.block")==null,"Colour stems");
            var strength=rules.get("sun.endFogStrength");require(!GraphicsCatalog.differs(strength,strength.neutral())&&GraphicsCatalog.differs(strength,strength.neutral()+strength.step()),"Changed values not detected");
            require(GraphicsCatalog.extreme("sun.endFogStrength",2)&&!GraphicsCatalog.extreme("sun.endFogStrength",1)&&GraphicsCatalog.extreme("sun.endFogDistance",32)&&!GraphicsCatalog.extreme("sun.block",3),"Extreme values");
            // Features: nothing lost in the split, every setting has one place, rain and wetness still follow the weather switch.
            int perFeature=0;for(var f:GraphicsCatalog.FEATURES)perFeature+=GraphicsCatalog.rules(f.id()).size();perFeature+=GraphicsCatalog.rules("").size();
            require(perFeature==rules.size(),"Settings lost or doubled across features: "+perFeature+" of "+rules.size());
            require(GraphicsCatalog.feature(rules.get("sun.dropWidth")).equals("rain")&&GraphicsCatalog.feature(rules.get("sun.wetPuddles")).equals("wetness")&&GraphicsCatalog.feature(rules.get("sun.weatherSun")).equals("weather"),"Weather split");
            require(GraphicsCatalog.requires("sun.weatherDrops").contains("sun.weather")&&GraphicsCatalog.requires("sun.wetSurfaces").contains("sun.weather")&&GraphicsCatalog.requires("sun.dropWidth").contains("sun.weatherDrops"),"Weather split dependencies");
            require(GraphicsCatalog.requires("sun.endFogHeight").contains("sun.endFog")&&GraphicsCatalog.requires("sun.endFogEmberColorG").contains("sun.endFogEmber")&&GraphicsCatalog.requires("sun.endFogHoleLight").contains("sun.endBlackHole"),"End fog look active without what it needs");
            for(String key:List.of("sun.endFog","sun.endFogStrength")){
                var rule=rules.get(key);
                require(rule!=null&&rule.group().equals("endSky")&&GraphicsCatalog.feature(rule).equals("endSky")&&GraphicsCatalog.tier(rule)==GraphicsCatalog.Tier.LOOK,"End fog setting in wrong section/tier");
                require(!GraphicsCatalog.requires(key).contains("sun.endPlanet")&&!GraphicsCatalog.requires(key).contains("sun.endBlackHole"),"End fog incorrectly depends on a celestial body");
            }
            require(rules.get("sun.endFog").toggle()&&GraphicsCatalog.requires("sun.endFog").contains("sun.skyEnabled"),"End fog switch/sky dependency");
            require(GraphicsCatalog.requires("sun.endFogStrength").contains("sun.endFog")&&GraphicsCatalog.requires("sun.endFogDistance").contains("sun.endFog"),"Fog sliders active when fog off");
            require(Math.abs(saved.get("sun.endFogStrength")-.65f)<1e-6&&saved.get("sun.endFogDistance")==192&&decoded.values().get("sun.endFogDistance")==192,"Fog settings lost in persistence/profile round trip");
            var featureIds=new HashSet<String>();for(var f:GraphicsCatalog.FEATURES)featureIds.add(f.id());
            var usedFeatures=new HashSet<String>();
            for(var rule:rules.values()){
                String f=GraphicsCatalog.feature(rule);require(f.isEmpty()||featureIds.contains(f),"Setting in no feature: "+rule.key());if(!f.isEmpty())usedFeatures.add(f);
                require(GraphicsCatalog.GROUP_ORDER.contains(rule.group()),"Group without a place: "+rule.group());
                if(rule.key().endsWith("R")&&rules.containsKey(rule.key().replaceAll("R$","G"))&&rules.containsKey(rule.key().replaceAll("R$","B")))
                    require(GraphicsCatalog.tier(rule)==GraphicsCatalog.tier(rules.get(rule.key().replaceAll("R$","G")))&&GraphicsCatalog.tier(rule)==GraphicsCatalog.tier(rules.get(rule.key().replaceAll("R$","B")))
                        &&rule.group().equals(rules.get(rule.key().replaceAll("R$","G")).group())&&rule.group().equals(rules.get(rule.key().replaceAll("R$","B")).group()),"Color channels torn apart: "+rule.key());
                Set<String> seen=new HashSet<>();Deque<String> open=new ArrayDeque<>(GraphicsCatalog.requires(rule.key()));
                while(!open.isEmpty()){String need=open.pop();require(rules.containsKey(need),"Unknown dependency: "+need);require(!need.equals(rule.key()),"Dependency cycle: "+rule.key());if(seen.add(need))open.addAll(GraphicsCatalog.requires(need));}
            }
            require(usedFeatures.equals(featureIds),"Empty feature in the menu");
            for(var f:GraphicsCatalog.FEATURES)if(f.toggle()!=null)require(rules.containsKey(f.toggle())&&GraphicsCatalog.feature(rules.get(f.toggle())).equals(f.id()),"Feature switch elsewhere: "+f.id());
            require(GraphicsCatalog.feature(rules.get("sun.moonGlare")).equals("celestial")&&GraphicsCatalog.feature(rules.get("sun.sunGlare")).equals("celestial")&&GraphicsCatalog.feature(rules.get("camera.enabled")).equals("camera")&&GraphicsCatalog.feature(rules.get("sun.shadowFill")).equals("shadows"),"Sun, moon or camera settings misplaced");
            require(!GraphicsCatalog.requires("sun.moonGlare").contains("sun.celestialStyle")&&GraphicsCatalog.requires("sun.moonGlare").contains("sun.skyEnabled")&&GraphicsCatalog.requires("sun.moonLight").contains("sun.moonShadows")&&GraphicsCatalog.requires("sun.rainbowStrength").contains("sun.rainbows"),"Dependencies changed");
            var order=new ArrayList<>(rules.keySet());require(order.indexOf("sun.sunGlare")==order.indexOf("sun.celestialSunGlow")+1&&order.indexOf("sun.moonGlare")==order.indexOf("sun.celestialMoonGlow")+1,"The two glows are not side by side");
            require(GraphicsCatalog.QUALITY_LEVELS.size()==5,"Quality levels changed");
            Path freshDir=Files.createDirectories(directory.resolve("fresh"));DefaultProfile.applyOnFirstStart(freshDir);
            GraphicsSettings freshSettings=new GraphicsSettings(freshDir);Map<String,Float> fresh=freshSettings.capture();
            for(var e:GraphicsCatalog.QUALITY_LEVELS.get(0).entrySet())require(Math.abs(fresh.get(e.getKey())-e.getValue())<1e-4f,"A fresh install is not on Standard: "+e.getKey());
            MenuSession qualityMenu=new MenuSession(freshSettings,DefaultProfile.ID);
            qualityMenu.setAll(GraphicsCatalog.QUALITY_LEVELS.get(1));qualityMenu.setAll(GraphicsCatalog.QUALITY_LEVELS.get(0));
            require(qualityMenu.values().equals(fresh),"Low then Standard must restore the shipped profile's quality without changing its look");
            qualityMenu.set("sun.renderScale",1);
            for(int index=0;index<GraphicsCatalog.QUALITY_LEVELS.size();index++){
                qualityMenu.setAll(GraphicsCatalog.QUALITY_LEVELS.get(4));
                require(qualityMenu.value("sun.coloredLight")==1,"Ultra must enable colored block light");
                qualityMenu.setAll(GraphicsCatalog.QUALITY_LEVELS.get(1));
                require(qualityMenu.value("sun.waterReflection")==0,"Low must disable water reflections");
                qualityMenu.setAll(GraphicsCatalog.QUALITY_LEVELS.get(index));
                require(qualityMenu.value("sun.ssao")==0,"SSAO enabled by quality level "+index);
                require(qualityMenu.value("sun.coloredLight")== (index==4?1:0),"Colored light leaked from Ultra into level "+index);
                require(Math.abs(qualityMenu.value("sun.waterReflection")-(index==1?0:fresh.get("sun.waterReflection")))<1e-4f,"Water reflections not restored after Low: "+index);
                require(qualityMenu.value("sun.renderScale")==1,"Quality switch changed MetalFX");
            }
            for(var level:GraphicsCatalog.QUALITY_LEVELS){require(level.keySet().equals(GraphicsCatalog.QUALITY_LEVELS.get(0).keySet()),"Levels set different settings");for(var e:level.entrySet())rules.get(e.getKey()).normalize(e.getValue());}
            require(GraphicsCatalog.QUALITY_LEVELS.get(4).containsKey("sun.ssaoQuality"),"SSAO left out of the quality levels");
            for(String lang:List.of("en_us","de_de"))try(var in=GraphicsMenuChecks.class.getResourceAsStream("/assets/siliconshaders/lang/"+lang+".json")){
                var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                for(var rule:rules.values())if(!GraphicsCatalog.feature(rule).isEmpty()&&!rule.key().matches(".*(Tint)[RGB]$")){
                    require(json.has(rule.label()),"Missing "+lang+" label: "+rule.key());
                    String text=json.get(rule.label()).getAsString();require(text.indexOf("%s")==text.lastIndexOf("%s"),"Label with two values: "+rule.key());
                }
                for(String key:List.of("key.siliconshaders.toggle","key.category.siliconshaders.shaders","siliconshaders.shaders.key.on","siliconshaders.shaders.key.off"))require(json.has(key),"Missing "+lang+" shader key text: "+key);
                for(var f:GraphicsCatalog.FEATURES)require(json.has("siliconshaders.menu.feature."+f.id())&&json.has("siliconshaders.menu.area."+f.area()),"Missing "+lang+" feature text: "+f.id());
                for(String group:GraphicsCatalog.GROUP_ORDER)require(json.has("siliconshaders.menu.group."+group),"Missing "+lang+" heading: "+group);
                for(String level:List.of("standard","low","medium","high","ultra"))require(json.has("siliconshaders.menu.quality."+level)&&json.has("siliconshaders.menu.quality."+level+".help"),"Missing "+lang+" level: "+level);
                require(!json.get("siliconshaders.menu.color.camera_vignette").getAsString().matches("Rot|Red"),"Vignette colour named after one channel");
            }
            protectedProfiles(directory.resolve("protected"));
            System.out.println("PASS: exact portable-code round trips, checksum/version/size/type limits, bounded JSON, look-only performance preservation, frozen old code, settings newer than a code left alone, out-of-range values fitted, future keys skipped, invalid-import isolation, saved/renamed/deleted profiles, live preview without disk writes, all-store commit, undo/cancel, atomic rollback, corrupt-library preservation, bilingual menu labels");
            System.out.println("Portable ALL code: "+encoded.length()+" characters for "+all.values().size()+" settings");
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }

    private static void protectedProfiles(Path directory)throws Exception{
        DefaultProfile.applyOnFirstStart(directory);
        GraphicsSettings settings=new GraphicsSettings(directory);ProfileStore store=new ProfileStore(directory);
        GraphicsProfile standard=DefaultProfile.get("Standard");Map<String,Float> original=standard.values();
        MenuSession unchanged=new MenuSession(settings,DefaultProfile.ID);unchanged.finish(store);
        require(store.profiles().isEmpty()&&store.active().equals(DefaultProfile.ID),"Unchanged Standard created a personal profile");

        MenuSession edited=new MenuSession(settings,DefaultProfile.ID);
        float exposure=edited.value("look.exposure")==2f?.5f:2f;edited.set("look.exposure",exposure);
        edited.finish(store,"Standard (Kopie)");
        GraphicsProfile first=store.find(edited.selected());
        require(first!=null&&!edited.builtIn()&&first.name().equals("Standard (Kopie)")&&first.values().get("look.exposure")==exposure,"Edited Standard was not copied and selected");
        ProfileStore loaded=new ProfileStore(directory);
        require(loaded.active().equals(first.id())&&loaded.find(first.id()).values().equals(first.values()),"New profile or active selection lost on reload");
        MenuSession reopened=new MenuSession(new GraphicsSettings(directory),loaded.active());
        require(reopened.matches(loaded.find(loaded.active())),"Saved personal copy no longer matches its settings");
        reopened.finish(loaded);require(loaded.profiles().size()==1,"Finishing a personal profile made another copy");

        MenuSession second=new MenuSession(settings,DefaultProfile.ID);second.replace(standard);second.set("look.exposure",exposure);
        second.finish(store,"Standard (Kopie)");
        require(store.profiles().size()==2&&store.find(second.selected()).name().equals("Standard (Kopie) (2)")&&store.find(first.id()).values().equals(first.values()),"Copy name collision overwrote an existing profile");
        require(DefaultProfile.get("Standard").values().equals(original),"Bundled Standard profile was changed");

        Path library=directory.resolve("config/siliconshaders-graphics-profiles.json");byte[] before=Files.readAllBytes(library);
        Map<String,Float> saved=settings.capture();
        MenuSession cancel=new MenuSession(settings,store.active());cancel.replace(standard);cancel.set("look.exposure",exposure);cancel.abandon();
        require(Arrays.equals(before,Files.readAllBytes(library))&&settings.capture().equals(saved)&&store.profiles().size()==2,"Cancel saved or selected a copy");
        rejects(()->{try{store.put(standard);}catch(IOException e){throw new UncheckedIOException(e);}},"Built-in profile accepted by personal profile store");
        rejects(()->{try{store.delete(DefaultProfile.ID);}catch(IOException e){throw new UncheckedIOException(e);}},"Built-in profile allowed to be deleted");

        MenuSession saveOpen=new MenuSession(settings,store.active());saveOpen.replace(standard);saveOpen.set("look.exposure",exposure);saveOpen.saveCopy(store,"Standard (Kopie)");
        String active=saveOpen.selected();Map<String,Float> checkpoint=Map.copyOf(saveOpen.values());
        require(new ProfileStore(directory).active().equals(active)&&!saveOpen.changed(),"Saving in the open menu did not activate the copy or reset the dirty state");
        saveOpen.set("look.exposure",exposure==2f?.5f:2f);saveOpen.abandon();
        require(settings.capture().equals(checkpoint)&&store.active().equals(active),"Cancel after saving reverted past the saved profile");

        // A failed settings transaction must publish neither the new copy nor its active ID. Retrying creates one copy.
        MenuSession failing=new MenuSession(settings,DefaultProfile.ID);failing.replace(standard);failing.set("look.exposure",exposure);
        int count=store.profiles().size();before=Files.readAllBytes(library);
        Path camera=directory.resolve("config/siliconshaders-camera.json"),backup=camera.resolveSibling("camera.saved");
        Files.move(camera,backup);Files.createDirectory(camera);boolean failed=false;
        try{failing.finish(store,"Standard (Kopie)");}catch(IOException expected){failed=true;}
        finally{Files.delete(camera);Files.move(backup,camera);}
        require(failed&&failing.selected().equals(DefaultProfile.ID)&&store.active().equals(active)&&store.profiles().size()==count&&Arrays.equals(before,Files.readAllBytes(library)),"Failed copy save changed profile selection or library");
        failing.finish(store,"Standard (Kopie)");
        require(store.profiles().size()==count+1&&store.active().equals(failing.selected()),"Retry failed to create and activate exactly one copy");
        System.out.println("PASS: protected Standard, automatic personal copy and activation, unchanged/cancel no copy, unique names, saved cancel baseline, transactional failure and retry");
    }
}
