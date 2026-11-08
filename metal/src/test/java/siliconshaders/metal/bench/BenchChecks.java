package siliconshaders.metal.bench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import siliconshaders.metal.BenchGpu;
import siliconshaders.metal.camera.CameraSettings;
import siliconshaders.metal.sun.SunSettings;
import siliconshaders.metal.sun.SunTuning;
import net.minecraft.core.BlockPos;

/** The benchmark's numbers and plans, without Minecraft, a window or a world. */
public final class BenchChecks {
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static boolean near(double a,double b) { return Math.abs(a-b)<1e-6; }
    private static boolean rejects(String json) {
        try { BenchPlan.parse(new Gson().fromJson(json,JsonObject.class));return false; } catch(RuntimeException expected) { return true; }
    }
    private static Map<String,Object> row(String scene,String variant,double ms,Map<String,String> path,Map<String,Object> seen) {
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("scene",scene);r.put("sceneLabel",scene);r.put("variant",variant);r.put("averageFrameMs",ms);r.put("frames",1000);r.put("commandBuffers",1000);
        if(!path.isEmpty()){r.put("pathOverrides",path);r.put("pathEffective",seen);}
        return r;
    }
    private static Map<String,Object> summary(List<Map<String,Object>> entries,String path,boolean shader) {
        return entries.stream().filter(e->path.equals(e.get("path"))&&Boolean.valueOf(shader).equals(e.get("shader"))).findFirst().orElseThrow();
    }
    private static void pathChecks(SunSettings.State own,float[] ownTuning,float[] ownCamera) {
        // The tester's profile: the display's own resolution, both shader states, every scene with its reference and its repeat.
        BenchPlan.Request paths=BenchPlan.profile("pfade",true);
        require(paths.usesPaths()&&paths.window().equals("fullscreen")&&paths.mode().equals(BenchPlan.DESKTOP_MODE),"Path profile display contract");
        for(String other:List.of("kurz","standard","voll","diagnose"))require(!BenchPlan.profile(other,false).usesPaths(),other+" would need the path flag");
        require(paths.minutes()>=8&&paths.minutes()<=15,"Path profile duration "+paths.minutes());
        java.util.Set<String> seen=new java.util.HashSet<>(),covered=new java.util.HashSet<>();
        for(var v:paths.variants()){
            require(seen.add(v.id()),"Duplicate path variant "+v.id());
            for(var e:v.path().entrySet())BenchPlan.validatePath(e.getKey(),e.getValue());
            require(BenchPlan.state(v,own).enabled()==(v.base()!=BenchPlan.Base.VANILLA),"Shader state of "+v.id());
            BenchPlan.tuning(v,ownTuning);BenchPlan.camera(v,ownCamera);
        }
        for(var scene:paths.scenes()){
            if(scene.only()!=null)for(String id:scene.only())require(seen.contains(id),"Unknown scene variant "+id);
            for(String id:seen)if(scene.only()==null||scene.only().contains(id))covered.add(id);
            if(!scene.id().equals("land-ende"))for(String id:List.of("standard","standard-ende","vanilla","vanilla-ende"))
                require(scene.only()==null||scene.only().contains(id),"Scene "+scene.id()+" lacks "+id);
        }
        require(covered.equals(seen),"Path variants without a scene");
        // Each pair forces both sides: the size cutoff must not decide what "on" measures.
        require(paths.variants().stream().filter(v->v.id().equals("pfad-uocc-an")).findFirst().orElseThrow().path().equals(Map.of("frag.uoccSizeSwitch","false")),"Occlusion-on still depends on the cutoff");
        // Requests: a custom variant may set a known path only, with a value in range; the mode is WxH or desktop.
        BenchPlan.Request custom=BenchPlan.parse(new Gson().fromJson("{\"variants\":[{\"id\":\"x\",\"path\":{\"tl.a1Split\":\"30\"}}]}",JsonObject.class));
        require(custom.usesPaths()&&custom.variants().getFirst().path().equals(Map.of("tl.a1Split","30")),"Custom path variant");
        require(BenchPlan.parse(new Gson().fromJson("{\"variants\":[\"pfad-grid-an\"]}",JsonObject.class)).usesPaths(),"Named path variant");
        require(BenchPlan.parse(new Gson().fromJson("{\"profile\":\"pfade\",\"scenes\":[\"wasser\"]}",JsonObject.class)).scenes().size()==1,"Path profile scene choice");
        require(BenchPlan.parse(new Gson().fromJson("{\"window\":\"fullscreen\",\"mode\":\"desktop\"}",JsonObject.class)).mode().equals("desktop"),"Desktop mode");
        require(rejects("{\"variants\":[{\"id\":\"x\",\"path\":{\"siliconshaders.own\":\"false\"}}]}")&&rejects("{\"variants\":[{\"id\":\"x\",\"path\":{\"frag.vGroupSh\":\"9\"}}]}")
            &&rejects("{\"variants\":[{\"id\":\"x\",\"path\":{\"frag.uocc\":\"maybe\"}}]}")&&rejects("{\"variants\":[{\"id\":\"x\",\"path\":{\"tl.a1Split\":\"100\"}}]}"),"Invalid path accepted");
        // The launch flag: exactly the requests that need it, as the tools write them.
        require(siliconshaders.metal.Profile.requestsPathVariants("{\"profile\": \"pfade\", \"label\": \"\"}")&&siliconshaders.metal.Profile.requestsPathVariants("{\"profile\":\"pfade\",\"quit\":true}")
            &&siliconshaders.metal.Profile.requestsPathVariants("{\"variants\":[{\"id\":\"x\",\"path\":{}}]}")&&!siliconshaders.metal.Profile.requestsPathVariants("{\"profile\":\"diagnose\",\"label\":\"pfade\"}"),"Path flag detection");
        // Without the flag every read is the default, and the benchmark refuses instead of measuring the wrong path.
        require(!siliconshaders.metal.own.OwnProbe.pathVariantsAvailable(),"Path flag on by default");
        siliconshaders.metal.own.OwnProbe.benchmark(Map.of("frag.uocc","false"));
        require(siliconshaders.metal.own.OwnProbe.bool("frag.uocc",true),"Override applied without the flag");siliconshaders.metal.own.OwnProbe.benchmark(Map.of());
        // Summary: a clear win, a difference inside the scene's noise, and an override the frames didn't show.
        Map<String,Object> uoccOn=Map.of("uoccShare",1.0),uoccOff=Map.of("uoccShare",0.0),gridOff=Map.of("receiverGridShare",0.0),gridOn=Map.of("receiverGridShare",1.0);
        List<Map<String,Object>> rows=List.of(row("land","standard",10,Map.of(),Map.of()),row("land","standard-ende",10.1,Map.of(),Map.of()),
            row("land","pfad-uocc-aus",8,Map.of("frag.uocc","false"),uoccOff),row("land","pfad-uocc-an",10,Map.of("frag.uoccSizeSwitch","false"),uoccOn),
            row("land","pfad-grid-aus",10,Map.of("sun.receiverGrid","false"),gridOff),row("land","pfad-grid-an",10.05,Map.of("sun.receiverGrid","true"),gridOn),
            row("land","pfad-a1-aus",9,Map.of("tl.a1Split","0"),Map.of("a1SplitShare",0.0)),row("land","pfad-a1-30",8,Map.of("tl.a1Split","30"),Map.of("a1SplitShare",0.0)),
            row("land","vanilla",4,Map.of(),Map.of()),row("land","vanilla-ende",6,Map.of(),Map.of()),
            row("land","aus-uocc-aus",3,Map.of("frag.uocc","false"),uoccOff),row("land","aus-uocc-an",3.5,Map.of("frag.uoccSizeSwitch","false"),uoccOn));
        var found=BenchPathSummary.analyze(rows);
        require("aus".equals(summary(found,"uocc",true).get("winner"))&&near((Double)summary(found,"uocc",true).get("gainPercent"),20),"Clear win "+summary(found,"uocc",true));
        require(summary(found,"grid",true).get("winner")==null&&"kein klarer Unterschied".equals(summary(found,"grid",true).get("verdict")),"Small difference called a win");
        require(summary(found,"a1",true).get("winner")==null&&summary(found,"a1",true).get("verdict").toString().contains("nicht wirksam"),"Ineffective override got a winner");
        require(summary(found,"uocc",false).get("winner")==null,"A difference below the drift called a win: "+summary(found,"uocc",false));
        var noted=BenchDiagnosis.analyze(rows);
        require(noted.stream().anyMatch(e->"pfad-a1-30".equals(e.get("variant"))&&e.get("notes").toString().contains("nicht wirksam")),"Ineffective override not noted");
        Map<String,Object> shaderOff=new LinkedHashMap<>(rows.get(10));shaderOff.put("referenceVariant","vanilla");
        require("vanilla".equals(BenchDiagnosis.analyze(List.of(rows.get(0),rows.get(8),shaderOff)).getLast().get("reference")),"Shader-off path compared with the shader on");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("started","2026-11-06 12:00:00");report.put("minutes",1.0);report.put("status","ok");report.put("siliconshaders","test");report.put("profile","pfade");report.put("secondsPerMeasurement",8);
        report.put("system",Map.of("chip","Apple M4","model","Mac16,10","gpuCores",10,"memoryGB",16,"macos","26.1","power","Netzteil","lowPower",false,"thermal",0,"thermalEnd",0));
        report.put("options",Map.of("window","Vollbild","renderDistance",12));report.put("results",List.of());report.put("warnings",List.of());report.put("pathSummary",found);
        String text=BenchReport.text(report);
        require(text.contains("Render-Pfade auf diesem Mac")&&text.contains("aus schneller, 20.0 %")&&text.contains("nicht wirksam"),text);
        System.out.println("PASS: path profile, launch flag, refusals, forced pairs and summary against noise");
    }
    public static void main(String[] args) throws Exception {
        // Regression: launcher-owned menus must not prevent a queued start. World teardown/reloads must still block.
        BenchStartGate gate=new BenchStartGate(0);
        require(gate.poll(0,true,true,true,false)==BenchStartGate.State.WORLD,"Started inside a world");
        require(gate.poll(100,false,true,false,false)==BenchStartGate.State.SERVER,"Started while server was saving");
        require(gate.poll(200,false,false,true,false)==BenchStartGate.State.CONNECTION,"Started with a live connection");
        require(gate.poll(300,false,false,false,true)==BenchStartGate.State.RESOURCES,"Started during resource loading");
        require(gate.poll(400,false,false,false,false)==BenchStartGate.State.SETTLE,"Skipped stable idle period");
        require(gate.poll(400+BenchStartGate.SETTLE_NANOS,false,false,false,false)==BenchStartGate.State.READY,"Custom main menu blocks launch");
        gate.poll(2_000_000_000L,false,false,false,true);
        require(gate.poll(3_000_000_000L,false,false,false,false)==BenchStartGate.State.SETTLE,"Reload did not restart settling");
        require(!gate.expired(BenchStartGate.TIMEOUT_NANOS-1)&&gate.expired(BenchStartGate.TIMEOUT_NANOS),"Pending request can hang forever");
        // The frame hook must not regress to an instanceof TitleScreen gate, which fails with Lunar's menu replacement.
        var reader=new org.objectweb.asm.ClassReader(ShaderBench.class.getName());boolean[] titleGate={false};
        reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9){
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                return !name.equals("frame")?null:new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9){
                    @Override public void visitTypeInsn(int opcode,String type){if(opcode==org.objectweb.asm.Opcodes.INSTANCEOF&&type.equals("net/minecraft/client/gui/screens/TitleScreen"))titleGate[0]=true;}
                };
            }
        },0);require(!titleGate[0],"Benchmark still requires the vanilla title screen");
        System.out.println("PASS: launcher-independent start, teardown/reload gates, stable idle period, timeout and production hook regression");
        // 99 frames of 10 ms and one of 50 ms in 1.04 s; the GPU busy 0.52 s.
        long[] frames=new long[100];for(int i=0;i<99;i++)frames[i]=10_000_000L;frames[99]=50_000_000L;
        BenchStats s=BenchStats.of(frames,100,1.04,0.52);
        require(near(s.fps(),100/1.04)&&near(s.low1(),20)&&near(s.p50(),10)&&near(s.p99(),10)&&near(s.max(),50),"Frame statistics "+s);
        require(near(s.gpuMs(),5.2)&&near(s.gpuBusy(),0.5),"GPU per frame/utilization "+s);
        require(BenchStats.of(frames,0,1,0).fps()==0&&BenchStats.of(frames,5,0,0).frames()==0,"Empty measurement");
        require(near(BenchStats.of(new long[]{1_000_000L},1,0.001,1).gpuBusy(),1),"Utilization above 100%");

        // Overlapping command buffers count once.
        require(near(BenchGpu.union(1,2,0),1)&&near(BenchGpu.union(1.5,3,2),1)&&BenchGpu.union(1,1.8,2)==0,"GPU interval union");

        // Profiles: the standard one is every scene with the five basic variants; land-ende only repeats the reference.
        BenchPlan.Request standard=BenchPlan.profile("standard",true);
        require(standard.quit()&&standard.scenes().size()==6&&standard.variants().size()==5&&standard.seconds()==10,"Standard profile");
        require(standard.scenes().getLast().only().equals(List.of("standard")),"End scene repeats the reference");
        require(BenchPlan.profile("voll",false).variants().size()>20&&BenchPlan.profile("kurz",false).variants().size()==2,"Full/short profiles");
        require(standard.minutes()>=5&&standard.minutes()<=12,"Standard profile duration "+standard.minutes());
        List<String> ids=new ArrayList<>();for(var v:BenchPlan.FULL){require(!ids.contains(v.id()),"Duplicate variant "+v.id());ids.add(v.id());}
        for(var v:BenchPlan.FULL)for(String key:v.tuning().keySet())require(SunTuning.index(key)>=0,"Unknown tuning value in "+v.id());

        // Variant settings: shipped defaults (on), off, the player's own, single changes.
        SunSettings.State own=new SunSettings.State(false,4096,false,128,4,false,false,false,false,false,false,false,false,true,3);
        SunSettings.State def=BenchPlan.state(BenchPlan.STANDARD,own);
        require(def.enabled()&&def.shadowSize()==1024&&def.smooth()&&def.shadowDistance()==64&&def.shadowLevels()==3&&def.gbuffer()&&def.farShadowCache(),"Standard state "+def);
        require(!BenchPlan.state(BenchPlan.VANILLA,own).enabled(),"Vanilla runs the shader");
        SunSettings.State mine=BenchPlan.state(BenchPlan.OWN,own);
        require(mine.enabled()&&mine.shadowSize()==4096&&mine.shadowDistance()==128&&mine.gbufferView()==0,"Own settings "+mine);
        for(var v:BenchPlan.FULL) {
            SunSettings.State state=BenchPlan.state(v,own);
            require(BenchPlan.with(state,Map.of()).equals(state),"with() changed an empty change");
            if(v.id().equals("gbuffer-aus"))require(state.enabled()&&!state.gbuffer()&&state.shadowSize()==1024,"G-buffer variant "+state);
            if(v.id().equals("schatten-aus"))require(state.shadowSize()==0,"Shadow-off variant");
        }
        float[] ownTuning=new float[SunTuning.COUNT];java.util.Arrays.fill(ownTuning,0.5f);
        float[] sky=BenchPlan.tuning(BenchPlan.FULL.stream().filter(v->v.id().equals("himmel-aus")).findFirst().orElseThrow(),ownTuning);
        require(sky[SunTuning.SKY_ENABLED]==0&&sky[0]==SunTuning.PARAMS.get(0).neutral(),"Tuning variant on neutral values");
        require(BenchPlan.tuning(BenchPlan.OWN,ownTuning)[0]==0.5f,"Own tuning");

        // A/B camera overrides retain every unrelated player setting and never mutate the saved snapshot.
        float[] ownCamera=CameraSettings.defaults();ownCamera[2]=.7f;
        BenchPlan.Request bloom=BenchPlan.parse(new Gson().fromJson("{\"variants\":[{\"id\":\"hdr\",\"base\":\"own\",\"camera\":{\"bloomVariant\":1}}]}",JsonObject.class));
        float[] hdr=BenchPlan.camera(bloom.variants().getFirst(),ownCamera);
        require(hdr[CameraSettings.BLOOM_VARIANT]==1&&hdr[2]==.7f&&ownCamera[CameraSettings.BLOOM_VARIANT]==0,"Camera A/B override changed original settings");
        require(BenchPlan.camera(BenchPlan.STANDARD,ownCamera)[2]==CameraSettings.defaults()[2],"Standard camera inherited player settings");
        require(rejects("{\"variants\":[{\"id\":\"x\",\"camera\":{\"unknown\":1}}]}")
            &&rejects("{\"variants\":[{\"id\":\"x\",\"camera\":{\"bloomVariant\":\"NaN\"}}]}"),"Invalid camera override accepted");

        // Requests: profile, custom variants, scenes; anything unknown is refused before the run.
        BenchPlan.Request r=BenchPlan.parse(new Gson().fromJson("{\"profile\":\"kurz\",\"seconds\":5,\"label\":\"m5 air\",\"quit\":true,"
            +"\"scenes\":[\"wald\"],\"variants\":[\"standard\",{\"id\":\"test\",\"base\":\"own\",\"sun\":{\"shadowSize\":2048,\"gbuffer\":false},\"tuning\":{\"waterReflection\":0}}]}",JsonObject.class));
        require(r.seconds()==5&&r.label().equals("m5-air")&&r.quit()&&r.scenes().size()==1&&r.variants().size()==2,"Parsed request "+r);
        SunSettings.State custom=BenchPlan.state(r.variants().get(1),own);
        require(custom.shadowSize()==2048&&!custom.gbuffer()&&custom.shadowDistance()==128,"Custom variant "+custom);
        require(BenchPlan.parse(new Gson().fromJson("{}",JsonObject.class)).profile().equals("standard"),"Default profile");
        BenchPlan.Request full=BenchPlan.parse(new Gson().fromJson("{\"window\":\"fullscreen\",\"mode\":\"1900x1020\"}",JsonObject.class));
        require(full.window().equals("fullscreen")&&full.mode().equals("1900x1020")&&BenchPlan.parse(new Gson().fromJson("{\"window\":\"odd\"}",JsonObject.class)).window().equals("fixed"),"Window modes");
        require(rejects("{\"window\":\"fullscreen\",\"mode\":\"big\"}"),"Bad display mode accepted");
        require(rejects("{\"scenes\":[\"mond\"]}")&&rejects("{\"variants\":[\"nope\"]}")&&rejects("{\"variants\":[{\"id\":\"x\",\"sun\":{\"shadowSize\":300}}]}")
            &&rejects("{\"variants\":[{\"id\":\"x\",\"sun\":{\"nope\":true}}]}")&&rejects("{\"variants\":[{\"id\":\"x\",\"tuning\":{\"nope\":1}}]}")
            &&rejects("{\"variants\":[{\"id\":\"x\",\"sun\":{\"gbuffer\":3}}]}"),"Invalid requests accepted");

        // Spot search: rings in a fixed order, the nearest match first.
        int[] hit=BenchSpots.spiral(new BlockPos(0,64,0),BenchSpots.RADIUS,(x,z)->Math.abs(x)>=32&&z==0);
        require(hit!=null&&hit[0]==-32&&hit[1]==0,"Spiral order "+java.util.Arrays.toString(hit));
        require(BenchSpots.spiral(BlockPos.ZERO,BenchSpots.RADIUS,(x,z)->false)==null,"Spiral without a match");

        // Report: a header and one line per measurement.
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("started","2026-10-31 12:00:00");out.put("minutes",6.5);out.put("status","ok");out.put("siliconshaders","test");out.put("profile","standard");out.put("secondsPerMeasurement",10);
        out.put("system",Map.of("chip","Apple M5","model","Mac17,1","gpuCores",10,"memoryGB",16,"macos","26.1","power","Akku","lowPower",false,"thermal",0,"thermalEnd",1));
        out.put("options",Map.of("window","1280x800","renderDistance",12));
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("sceneLabel","Wald");m.put("variantLabel","Standard");m.put("fps",88.5);m.put("low1",61.0);m.put("p95Ms",13.0);m.put("p99Ms",16.0);
        m.put("gpuMs",10.5);m.put("gpuBusy",0.97);m.put("thermalStart",0);m.put("thermalEnd",1);
        out.put("results",List.of(m));out.put("warnings",List.of("test"));
        String text=BenchReport.text(out);
        require(text.contains("Apple M5")&&text.contains("10 GPU-Kerne")&&text.contains("88.5")&&text.contains("97 %")&&text.contains("normal → leicht")&&text.contains("Hinweis: test"),text);
        // Complete render diagnostics: every scheduled case is valid, unique, and has a standard reference.
        var diagnose=BenchPlan.profile("diagnose",false);int cases=0;
        require(diagnose.window().equals("fullscreen")&&diagnose.mode().equals("1920x1200"),"Diagnosis display contract");
        java.util.Set<String> coverage=new java.util.HashSet<>();
        for(var scene:diagnose.scenes()){
            int references=0;
            for(var variant:diagnose.variants())if(scene.only()==null||scene.only().contains(variant.id())){
                cases++;coverage.add(variant.id());if(variant.id().equals("standard"))references++;
                BenchPlan.state(variant,own);BenchPlan.tuning(variant,ownTuning);CameraSettings.mask(BenchPlan.camera(variant,ownCamera));
                require(BenchPlan.look(variant,siliconshaders.metal.sun.SunLook.Values.NEUTRAL)!=null,"Missing look");
            }
            require(references==1,"Missing reference in "+scene.id());
            if(scene.only()!=null)for(String id:scene.only())require(diagnose.variants().stream().anyMatch(v->v.id().equals(id)),"Unknown scene variant "+id);
        }
        require(cases>=90&&cases<=130&&coverage.size()==diagnose.variants().size(),"Incomplete diagnosis coverage: "+cases+" / "+coverage.size());
        require(diagnose.minutes()>=15&&diagnose.minutes()<=30,"Unexpected duration "+diagnose.minutes());
        for(String key:List.of("bloom","dof","motion","flare","chroma","vignette","sharpen","godrays")){
            var v=diagnose.variants().stream().filter(x->x.id().equals("kamera-"+key)).findFirst().orElseThrow();
            require(BenchPlan.camera(v,ownCamera)[CameraSettings.index(key)]==1,"Camera effect not activated: "+key);
        }
        require(BenchPlan.look(BenchPlan.STANDARD,new siliconshaders.metal.sun.SunLook.Values(2,2,2)).exposure()==1,"Reference inherited player grading");
        require(rejects("{\"variants\":[{\"id\":\"x\",\"tuning\":{\"ssao\":\"NaN\"}}]}")&&rejects("{\"scenes\":[]}")&&rejects("{\"variants\":[\"standard\",\"standard\"]}"),"Invalid diagnostic request accepted");
        BenchGpu.start();long old=BenchGpu.epoch();BenchGpu.stage(old,"test",1);BenchGpu.stop();BenchGpu.start();
        BenchGpu.stage(old,"stale",2);BenchGpu.stage(BenchGpu.epoch(),"current",3);
        require(!BenchGpu.details().toString().contains("stale")&&BenchGpu.details().toString().contains("current"),"GPU samples leaked between variants");BenchGpu.stop();
        Map<String,Object> reference=new LinkedHashMap<>(Map.of("scene","land","variant","standard","averageFrameMs",2.0,"frames",1000,"commandBuffers",1000));
        Map<String,Object> slow=new LinkedHashMap<>(reference);slow.put("variant","metalfx-67");slow.put("averageFrameMs",143.0);slow.put("tuningSettings",Map.of("renderScale",2));slow.put("upscaleFrames",0);slow.put("thermalEnd",2);
        var diagnosis=BenchDiagnosis.analyze(List.of(reference,slow)).getLast();
        require(near(((Number)diagnosis.get("deltaFrameMs")).doubleValue(),141)&&diagnosis.get("notes").toString().contains("kein Upscaling"),"Missing slow/fallback diagnosis: "+diagnosis);
        require(BenchDiagnosis.analyze(List.of(slow)).getFirst().get("deltaFrameMs")==null,"Comparison invented without a reference");
        Map<String,Object> htmlData=new LinkedHashMap<>(out);htmlData.put("label","</script><script>alert(1)</script>");
        String html=BenchHtml.render(htmlData);require(!html.contains("__SILICONSHADERS_DATA__")&&!html.contains("<script>alert(1)"),"Unsafe HTML report embedding");
        java.nio.file.Path gameLog=java.nio.file.Files.createTempFile("siliconshaders-bench-log-",".txt");
        try{
            java.nio.file.Files.writeString(gameLog,"native shader context\n");m.put("frameTimesMs",List.of(10,11));
            String combined=BenchLog.text(out,"Case 1/1 complete\n",gameLog);
            require(combined.contains("Case 1/1 complete")&&combined.contains("native shader context")&&combined.contains("Apple M5")&&!combined.contains("\"frameTimesMs\""),"Incomplete diagnostic log");
        }finally{java.nio.file.Files.deleteIfExists(gameLog);}
        pathChecks(own,ownTuning,ownCamera);
        System.out.println("PASS: "+cases+" diagnostic cases, effect activation, GPU epochs, fallback/drift analysis and HTML escaping");
        System.out.println("PASS: frame statistics, GPU interval union, profiles, variant settings, request parsing/refusal, spot spiral and report");
    }
}
