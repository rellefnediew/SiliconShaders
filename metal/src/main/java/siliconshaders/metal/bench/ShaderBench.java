package siliconshaders.metal.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import java.util.concurrent.CompletableFuture;
import siliconshaders.metal.BenchGpu;
import siliconshaders.metal.sun.SunSettings;
import siliconshaders.metal.sun.SunTuning;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * The shader benchmark: always the same world (fixed seed), the same camera spots, the same game options and the same
 * window size, then every scene x settings variant of the plan (BenchPlan): warm up, then measure frame times and the
 * GPU's busy time while the camera turns once around. Results go to siliconshaders-benchmarks/ in the game folder (.json for
 * tools/benchmark-compare, .txt to read). The player's options and shader settings are only changed in memory and
 * put back at the end, also on Esc (which aborts) or an error.
 * <p>
 * Started by config/siliconshaders-benchmark.json at launch (tools/benchmark writes it; the file is consumed) or from the shader
 * menu. Off, the two hooks (Minecraft.runTick and renderFrame) cost one static read each.
 */
public final class ShaderBench {
    static final String WORLD="siliconshaders-benchmark";
    static final long SEED=26_031_031L;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static volatile boolean active;
    private static String progress="";
    public static String progress(){return progress;}
    /** Fixed wetness prevents weather history from contaminating comparisons and the final reference. */
    public static float wetness(){return run!=null&&run.pair!=null&&run.pair.scene().id().equals("regen")?1:0;}
    private static Run run;
    private static BenchPlan.Request pending;
    /** A request file that could not be used: its reason, and whether it asked to quit afterwards. */
    private static String rejected;private static boolean rejectedQuit;
    private static boolean requestRead;
    private static BenchStartGate startGate;

    private ShaderBench() {}
    /** True while a benchmark drives the game (the frame-limit mixin keeps the full frame rate then). */
    public static boolean active() { return active; }
    public static boolean busy() { return run!=null||pending!=null; }
    /** From the shader menu: leaves the current world, then runs in the benchmark world and comes back to the title screen. */
    public static void start(String profile) {
        if(busy())return;
        pending=BenchPlan.profile(profile,false);requestRead=true;rejected=null;rejectedQuit=false;
        startGate=new BenchStartGate(System.nanoTime());
        Minecraft mc=Minecraft.getInstance();
        siliconshaders.metal.Log.info("[SiliconShaders bench] Menu request: "+profile+", screen="+(mc.gui.screen()==null?"none":mc.gui.screen().getClass().getName()));
        if(mc.level!=null)mc.disconnectFromWorld(Component.translatable("siliconshaders.bench.leaving"));
        mc.gui.setScreen(new BenchStartScreen());
    }

    static Component startMessage(){
        return Component.translatable("siliconshaders.bench.wait."+(startGate==null?"settle":startGate.state().name().toLowerCase(Locale.ROOT)));
    }
    static void cancelPending(){
        if(run!=null)return;
        siliconshaders.metal.Log.info("[SiliconShaders bench] Pending start cancelled");
        pending=null;rejected=null;startGate=null;
        Minecraft.getInstance().gui.setScreen(new TitleScreen());
    }

    /** Minecraft.runTick, before anything else of the frame. */
    public static void frame(Minecraft mc) {
        long now=System.nanoTime();
        if(run!=null) {
            try { run.frame(mc,now); } catch(Throwable failure) { run.fail(mc,failure); }
            return;
        }
        if(!requestRead) { requestRead=true;readRequest(mc); }
        if(pending==null&&rejected==null)return;
        // Lunar and other launchers replace TitleScreen. Screen identity says nothing about teardown readiness.
        if(startGate==null)startGate=new BenchStartGate(now);
        BenchStartGate.State state=startGate.poll(now,mc.level!=null,mc.getSingleplayerServer()!=null,mc.getConnection()!=null,mc.gui.overlay()!=null);
        if(state!=BenchStartGate.State.READY){
            if(startGate.expired(now)){
                rejectedQuit=pending!=null&&pending.quit();pending=null;
                rejected="Benchmark-Start nach 120 Sekunden abgebrochen: "+state;
                startGate=null;reject(mc);
            }
            return;
        }
        startGate=null;
        if(rejected!=null) { reject(mc);return; }
        BenchPlan.Request request=pending;pending=null;
        siliconshaders.metal.Log.info("[SiliconShaders bench] Launch ready; screen="+(mc.gui.screen()==null?"none":mc.gui.screen().getClass().getName()));
        run=new Run(request,mc,now);active=true;
        try{run.originals.applyBenchmark(mc,request,run);run.enter(Phase.OPEN,System.nanoTime());}catch(Throwable failure){run.fail(mc,failure);}
    }
    /** Minecraft.renderFrame: the scripted camera for this instant. */
    public static void beforeRender(Minecraft mc) { if(run!=null)run.pose(mc,System.nanoTime()); }

    private static void readRequest(Minecraft mc) {
        Path file=mc.gameDirectory.toPath().resolve("config/siliconshaders-benchmark.json");
        if(!Files.isRegularFile(file))return;
        String text="";
        try {
            text=Files.readString(file);
            Files.delete(file);  // consumed first: a crash must not start it again on the next launch
            BenchPlan.Request parsed=BenchPlan.parse(JSON.fromJson(text,JsonObject.class));
            // Measuring the configured path under a path variant's name would look like a result.
            if(parsed.usesPaths()&&!siliconshaders.metal.own.OwnProbe.pathVariantsAvailable())throw new IllegalStateException("Render-Pfade lassen sich in diesem Start nicht umschalten (eigener Renderer aus oder -Dsiliconshaders.own.probe gesetzt)");
            pending=parsed;
            startGate=new BenchStartGate(System.nanoTime());
            siliconshaders.metal.Log.info("[SiliconShaders bench] Request "+file+": profile "+pending.profile()+", "+pending.seconds()+" s per measurement");
        } catch(Exception failure) {
            siliconshaders.metal.Log.warn("[SiliconShaders bench] Request ignored: "+failure);
            rejected=failure.getMessage()!=null?failure.getMessage():failure.toString();
            rejectedQuit=text.matches("(?s).*\"quit\"\\s*:\\s*true.*");
        }
    }
    /** An unusable request still ends like a run: a result file with the reason (tools/benchmark prints it), then quit if asked. */
    private static void reject(Minecraft mc) {
        String reason="Benchmark konnte nicht starten: "+rejected;rejected=null;
        Path written=null;
        try {
            Path dir=mc.gameDirectory.toPath().resolve("siliconshaders-benchmarks");Files.createDirectories(dir);
            String name=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+"-fehler";
            atomic(dir.resolve(name+".txt"),"siliconshaders Shader-Benchmark: "+reason+"\n");
            Path logfile=dir.resolve(name+".log");atomic(logfile,"SiliconShaders Benchmark: "+reason+"\n");
            new ProcessBuilder("open","-R",logfile.toString()).start();
            Map<String,Object> out=new LinkedHashMap<>();out.put("format",1);out.put("status",reason);out.put("results",List.of());
            written=dir.resolve(name+".json");atomic(written,JSON.toJson(out)+"\n");
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders bench] Could not write the refusal: "+failure); }
        if(rejectedQuit)mc.stop();
        else mc.gui.setScreen(new BenchResultScreen(new TitleScreen(),List.of(),written,reason));
    }

    private enum Phase { OPEN, JOIN, SETUP, SPOTS, SCENE, SETTLE, WARM, MEASURE }
    private record Pair(BenchPlan.Scene scene,BenchPlan.Variant variant) {}

    private static final class Run {
        final BenchPlan.Request request;
        final long begun;
        final String reportName=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        final String started=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        final Originals originals;
        final Map<String,Object> system;
        final List<Map<String,Object>> results=new ArrayList<>();
        final List<String> warnings=new ArrayList<>();
        final StringBuilder log=new StringBuilder();
        String lastScreen="";
        final List<Pair> pairs=new ArrayList<>();
        Phase phase=Phase.OPEN;long phaseStart;
        boolean created;
        CompletableFuture<Map<String,double[]>> spotSearch;
        CompletableFuture<Void> fixtureSetup;
        Map<String,double[]> spots;
        int index=-1;Pair pair;double[] at;
        int lastChunks=-1;long chunkChange,notBuilt,lastStall;
        final long[] frames=new long[1<<18];int count;long lastFrame,measureStart;
        int thermalStart;BenchRuntime.Sample runtimeStart;long[] scaleStart,pathStart;
        Map<String,Object> measuredOptions,lastReport;int measuredWidth,measuredHeight;boolean lostFocus,resized,warmTimedOut;

        Run(BenchPlan.Request request,Minecraft mc,long now) {
            this.request=request;begun=now;
            system=BenchSystem.describe();
            originals=new Originals(mc);
            say("Start: "+request.profile()+" on "+system.get("chip")+", about "+request.minutes()+" min");
            enter(Phase.OPEN,now);
        }
        void enter(Phase next,long now) {
            phase=next;phaseStart=now;
            progress=Component.translatable("siliconshaders.bench.progress",Math.max(0,index+1),pairs.size(),pair==null?"":pair.scene().label()+" / "+pair.variant().label(),next.name()).getString();
        }
        /** The console and the run's own .log next to the results (the test app's console is not kept). */
        void say(String line) {
            siliconshaders.metal.Log.info("[SiliconShaders bench] "+line);
            log.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append(' ').append(line).append('\n');
        }
        double since(long now) { return (now-phaseStart)/1e9; }

        void frame(Minecraft mc,long now) {
            String screen=mc.gui.screen()==null?"none":mc.gui.screen().getClass().getSimpleName();
            if(!screen.equals(lastScreen)) { say("Screen: "+screen+(mc.isWindowActive()?"":" (window not focused)"));lastScreen=screen; }
            if(phase.ordinal()>=Phase.SCENE.ordinal()&&mc.gui.screen() instanceof PauseScreen) { finish(mc,"Abgebrochen (Esc)");return; }
            switch(phase) {
                case OPEN -> {
                    if(mc.getLevelSource().levelExists(WORLD))mc.createWorldOpenFlows().openWorld(WORLD,()->fail(mc,new IllegalStateException("World loading cancelled")));
                    else {
                        created=true;
                        LevelSettings settings=new LevelSettings(WORLD,GameType.CREATIVE,new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(WORLD,settings,new WorldOptions(SEED,true,false),WorldPresets::createNormalWorldDimensions,mc.gui.screen());
                    }
                    enter(Phase.JOIN,now);
                }
                case JOIN -> {
                    if(since(now)>600)throw new IllegalStateException("The benchmark world did not load within 10 minutes");
                    if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
                    if(mc.gui.screen()!=null) { if(since(now)>30&&!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen))mc.gui.setScreen(null);return; }
                    enter(Phase.SETUP,now);
                }
                case SETUP -> {
                    MinecraftServer server=mc.getSingleplayerServer();
                    if(server.overworld().getSeed()!=SEED)throw new IllegalStateException("Benchmark world seed differs; rename saves/"+WORLD+" before retrying");
                    for(String c:new String[]{"gamerule send_command_feedback false","gamerule log_admin_commands false","gamerule advance_time false",
                        "gamerule advance_weather false","gamerule random_tick_speed 0","gamerule spawn_mobs false","gamerule spawn_monsters false",
                        "gamerule spawn_patrols false","gamerule spawn_phantoms false","gamerule spawn_wandering_traders false",
                        "gamerule show_advancement_messages false","difficulty peaceful","gamemode spectator @a","weather clear","time set 4000"})
                        command(server,c);
                    Path file=spotFile(mc);
                    spots=BenchSpots.load(file);
                    if(spots==null) {
                        say("Searching the camera spots (once per world)");
                        spotSearch=server.submit(()->BenchSpots.find(server.overworld()));
                    }
                    enter(Phase.SPOTS,now);
                }
                case SPOTS -> {
                    if(spots==null) {
                        if(!spotSearch.isDone()) { if(since(now)>600)throw new IllegalStateException("Spot search took over 10 minutes");return; }
                        spots=spotSearch.join();say("Spots: "+String.join(", ",spots.keySet()));
                        try { BenchSpots.save(spotFile(mc),spots); } catch(Exception failure) { warnings.add("Spots not saved: "+failure.getMessage()); }
                    }
                    if(request.scenes().stream().anyMatch(s->s.spot().equals("objekte"))){
                        if(fixtureSetup==null){say("Preparing material, light and entity fixture");fixtureSetup=mc.getSingleplayerServer().submit(()->{BenchFixtures.prepare(mc.getSingleplayerServer(),spots);return null;});return;}
                        if(!fixtureSetup.isDone()){if(since(now)>600)throw new IllegalStateException("Fixture setup timeout");return;}fixtureSetup.join();
                    }
                    measuredOptions=originals.benchmarkOptions();
                    if(pairs.isEmpty())for(BenchPlan.Scene scene:request.scenes()) {
                        if(!spots.containsKey(scene.spot())) { warnings.add("Szene "+scene.id()+": kein passender Ort in der Welt gefunden, übersprungen");continue; }
                        for(BenchPlan.Variant variant:request.variants())
                            if(scene.only()==null||scene.only().contains(variant.id()))pairs.add(new Pair(scene,variant));
                    }
                    if(pairs.isEmpty())throw new IllegalStateException("No benchmark scenes are available");
                    next(mc,now);
                }
                case SCENE -> enter(Phase.SETTLE,now);
                case SETTLE -> {
                    int chunks=mc.level.getChunkSource().getLoadedChunksCount();
                    if(chunks!=lastChunks) { lastChunks=chunks;chunkChange=now; }
                    if(!mc.levelRenderer.hasRenderedAllSections())notBuilt=now;
                    double min=index==0?8:4;
                    boolean settled=since(now)>=min&&(now-chunkChange)/1e9>=2&&(now-notBuilt)/1e9>=1.5;
                    if(!settled&&since(now)>120) { warnings.add("Szene "+pair.scene().id()+": Chunks nach 120 s nicht fertig, trotzdem gemessen");settled=true; }
                    if(settled)warm(mc,System.nanoTime());
                }
                case WARM -> {
                    // At least the warm-up time, and one quiet second (no frame over 250 ms: shader compiles, reallocation).
                    if(now-lastFrame>250_000_000L)lastStall=now;
                    lastFrame=now;
                    if(since(now)>=BenchPlan.WARM_SECONDS&&(now-lastStall)/1e9>=1||since(now)>20) {
                        warmTimedOut=since(now)>20&&(now-lastStall)/1e9<1;
                        originals.validateDisplay(mc,request);
                        if(mc.getFramerateLimitTracker().getFramerateLimit()!=Integer.MAX_VALUE||mc.options.enableVsync().get())throw new IllegalStateException("FPS cap or VSync still active");
                        thermalStart=BenchSystem.thermal();runtimeStart=BenchRuntime.sample();scaleStart=siliconshaders.metal.own.RenderScale.benchmarkCounters();pathStart=siliconshaders.metal.own.OwnProbe.counters();
                        measuredWidth=mc.getWindow().getWidth();measuredHeight=mc.getWindow().getHeight();lostFocus=false;resized=false;
                        enter(Phase.MEASURE,System.nanoTime());measureStart=phaseStart;lastFrame=phaseStart;count=0;BenchGpu.start();
                    }
                }
                case MEASURE -> {
                    if(!mc.isWindowActive())lostFocus=true;
                    if(mc.getWindow().getWidth()!=measuredWidth||mc.getWindow().getHeight()!=measuredHeight)resized=true;
                    if(count>=frames.length)throw new IllegalStateException("Frame sample capacity exceeded");
                    frames[count++]=now-lastFrame;
                    lastFrame=now;
                    if((now-measureStart)/1e9>=request.seconds())record(mc,now);
                }
            }
        }
        void warm(Minecraft mc,long now) { enter(Phase.WARM,now);lastFrame=now;lastStall=now; }

        /** The next pair: a new scene teleports and settles, a new variant of the same scene only warms up. */
        void next(Minecraft mc,long now) {
            index++;
            if(index>=pairs.size()) { finish(mc,null);return; }
            Pair previous=pair;pair=pairs.get(index);
            originals.applyVariant(pair.variant());
            say("Case "+(index+1)+"/"+pairs.size()+": "+pair.scene().id()+" / "+pair.variant().id());
            try{write(mc,"läuft");}catch(Exception failure){warnings.add("Zwischenstand nicht gespeichert: "+failure);}
            now=System.nanoTime();
            if(previous!=null&&previous.scene()==pair.scene()) { warm(mc,now);return; }
            at=spots.get(pair.scene().spot());
            command(mc.getSingleplayerServer(),pair.scene().id().equals("regen")?"weather rain":"weather clear");
            command(mc.getSingleplayerServer(),String.format(Locale.ROOT,"time set %d",pair.scene().time()));
            command(mc.getSingleplayerServer(),String.format(Locale.ROOT,"tp @a %.3f %.3f %.3f %.1f %.1f",at[0],at[1],at[2],yaw(now),pair.scene().pitch()));
            lastChunks=-1;chunkChange=notBuilt=now;
            say("Scene "+pair.scene().id()+" at "+java.util.Arrays.toString(at)+", "+mc.getWindow().getWidth()+"x"+mc.getWindow().getHeight());
            enter(Phase.SCENE,now);
        }
        void record(Minecraft mc,long now) {
            BenchRuntime.Sample runtimeEnd=BenchRuntime.sample();
            double[] gpu=BenchGpu.stop();
            BenchStats stats=BenchStats.of(frames,count,(now-measureStart)/1e9,gpu[0]);
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("cameraSettings",siliconshaders.metal.camera.CameraSettings.get().describe());
            m.put("sunSettings",SunSettings.get().state());
            var tune=new LinkedHashMap<String,Float>();for(var p:SunTuning.PARAMS)tune.put(p.key(),SunTuning.get().value(SunTuning.index(p.key())));m.put("tuningSettings",tune);
            m.put("lookSettings",com.google.gson.JsonParser.parseString(siliconshaders.metal.sun.SunLook.get().json()));
            m.put("runtime",BenchRuntime.between(runtimeStart,runtimeEnd,count));m.put("gpuDetail",BenchGpu.details());
            long[] scaleEnd=siliconshaders.metal.own.RenderScale.benchmarkCounters();
            m.put("renderPath",siliconshaders.metal.own.RenderScale.benchmarkStatus());
            m.put("scaledFrames",scaleEnd[0]-scaleStart[0]);m.put("hdrFrames",scaleEnd[1]-scaleStart[1]);m.put("upscaleFrames",scaleEnd[2]-scaleStart[2]);
            m.put("warmupTimedOut",warmTimedOut);
            m.put("measurementValid",count>0&&!resized&&measuredWidth==mc.getWindow().getWidth()&&measuredHeight==mc.getWindow().getHeight());
            m.put("exclusiveFullscreen",mc.getWindow().isExclusiveFullscreen()&&mc.options.fullscreen().get());
            m.put("activeVideoMode",String.valueOf(mc.getWindow().getActiveVideoMode()));m.put("effectiveFpsLimit",mc.getFramerateLimitTracker().getFramerateLimit());m.put("vsync",mc.options.enableVsync().get());
            m.put("lostFocus",lostFocus);m.put("rainLevel",mc.level.getRainLevel(1));
            m.put("fixedWetness",ShaderBench.wetness());
            m.put("loadedChunks",mc.level.getChunkSource().getLoadedChunksCount());m.put("allSectionsRendered",mc.levelRenderer.hasRenderedAllSections());
            m.put("averageFrameMs",count>0?(now-measureStart)/1e6/count:0);
            List<Double> raw=new ArrayList<>(count);for(int i=0;i<count;i++)raw.add(round(frames[i]/1e6,4));m.put("frameTimesMs",raw);
            m.put("scene",pair.scene().id());m.put("sceneLabel",pair.scene().label());
            m.put("variant",pair.variant().id());m.put("variantLabel",pair.variant().label());
            if(pair.variant().base()==BenchPlan.Base.VANILLA&&!pair.variant().id().equals("vanilla"))m.put("referenceVariant","vanilla");
            if(!pair.variant().path().isEmpty()){m.put("pathOverrides",pair.variant().path());m.put("pathEffective",siliconshaders.metal.own.OwnProbe.effective(pathStart,siliconshaders.metal.own.OwnProbe.counters()));}
            m.put("frames",stats.frames());m.put("seconds",round(stats.seconds(),2));
            m.put("fps",round(stats.fps(),1));m.put("low1",round(stats.low1(),1));
            m.put("p50Ms",round(stats.p50(),3));m.put("p95Ms",round(stats.p95(),3));m.put("p99Ms",round(stats.p99(),3));m.put("maxMs",round(stats.max(),3));
            m.put("gpuMs",round(stats.gpuMs(),3));m.put("gpuBusy",round(stats.gpuBusy(),3));m.put("commandBuffers",(long)gpu[1]);
            m.put("thermalStart",thermalStart);m.put("thermalEnd",BenchSystem.thermal());m.put("lowPower",BenchSystem.lowPower());
            m.put("framebuffer",new int[]{mc.getWindow().getWidth(),mc.getWindow().getHeight()});
            results.add(m);
            say(String.format(Locale.ROOT,"%s / %s: %.1f fps, 1%% low %.1f, GPU %.2f ms (%.0f%%)",pair.scene().id(),pair.variant().id(),stats.fps(),stats.low1(),stats.gpuMs(),stats.gpuBusy()*100));
            next(mc,now);
        }
        double yaw(long now) {
            // One full turn per measurement; the warm-up turns along so the measurement starts at yaw 0.
            double speed=360.0/request.seconds();
            return switch(phase) {
                case WARM -> -speed*Math.max(0,BenchPlan.WARM_SECONDS-since(now));
                case MEASURE -> speed*(now-measureStart)/1e9;
                default -> -speed*BenchPlan.WARM_SECONDS;
            };
        }
        void pose(Minecraft mc,long now) {
            LocalPlayer player=mc.player;
            if(at==null||player==null||phase.ordinal()<Phase.SCENE.ordinal())return;
            float yaw=(float)yaw(now),pitch=pair.scene().pitch();
            player.getAbilities().flying=true;player.setDeltaMovement(Vec3.ZERO);
            player.snapTo(at[0],at[1],at[2],yaw,pitch);player.setYHeadRot(yaw);player.setYBodyRot(yaw);
        }
        void fail(Minecraft mc,Throwable failure) {
            say("Failed: "+failure);for(StackTraceElement e:failure.getStackTrace())log.append("    at ").append(e).append('\n');siliconshaders.metal.Log.failure(failure);
            finish(mc,"Fehler: "+failure.getMessage());
        }
        void finish(Minecraft mc,String problem) {
            if(run!=this)return;
            run=null;active=false;BenchGpu.stop();
            if(measuredOptions==null)measuredOptions=originals.benchmarkOptions();
            try{originals.restore(mc);}catch(Exception failure){warnings.add("Einstellungen konnten nicht vollständig wiederhergestellt werden: "+failure);}
            Path written=null;
            try { written=write(mc,problem); } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders bench] Results not written: "+failure); }
            say((problem==null?"Done":problem)+(written==null?"":": "+written));
            if(written!=null)try {
                Path logfile=written.resolveSibling(written.getFileName().toString().replace(".json",".log"));
                atomic(logfile,BenchLog.text(lastReport,log.toString(),mc.gameDirectory.toPath().resolve("logs/latest.log")));
                new ProcessBuilder("open","-R",logfile.toString()).start();
            }catch(Exception failure){siliconshaders.metal.Log.warn("[SiliconShaders bench] Could not finalize/reveal log in Finder: "+failure);}
            if(request.quit()) { mc.stop();return; }
            // Esc: stay in the benchmark world on the pause screen; the results so far are written.
            if(problem!=null&&problem.startsWith("Abgebrochen"))return;
            if(mc.level!=null) { mc.disconnectFromWorld(Component.translatable("siliconshaders.bench.leaving"));mc.gui.setScreen(new BenchResultScreen(new TitleScreen(),results,written,problem)); }
            else mc.gui.setScreen(new BenchResultScreen(new TitleScreen(),results,written,problem));
        }
        Path write(Minecraft mc,String problem) throws java.io.IOException {
            Path dir=mc.gameDirectory.toPath().resolve("siliconshaders-benchmarks");Files.createDirectories(dir);
            String chip=String.valueOf(system.get("chip")).replace("Apple ","").replaceAll("[^A-Za-z0-9]+","-").replaceAll("^-|-$","");
            String name=reportName+"-"+(chip.isEmpty()?"mac":chip)+(request.label().isEmpty()?"":"-"+request.label());
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("format",2);out.put("suiteVersion",1);out.put("label",request.label());out.put("profile",request.profile());out.put("secondsPerMeasurement",request.seconds());
            out.put("started",started);out.put("minutes",round((System.nanoTime()-begun)/6e10,1));out.put("status",problem==null?"ok":problem);
            out.put("siliconshaders",FabricLoader.getInstance().getModContainer("siliconshaders").map(c->c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
            Map<String,Object> sys=new LinkedHashMap<>(system);if(!request.power().isEmpty())sys.put("powerScript",request.power());
            sys.put("thermalEnd",BenchSystem.thermal());out.put("system",sys);
            out.put("options",measuredOptions==null?originals.benchmarkOptions():measuredOptions);
            out.put("currentCase",pair==null?"world-setup":pair.scene().id()+" / "+pair.variant().id());out.put("completedCases",results.size());out.put("plannedCases",pairs.size());
            out.put("diagnostics",BenchDiagnosis.analyze(results));
            if(request.usesPaths())out.put("pathSummary",BenchPathSummary.analyze(results));
            out.put("measurementNotes",List.of("Render- und Feature-Diagnose, kein vollständiger Korrektheits- oder Weltgenerierungs-Test.",
                "GPU-Last ist die Vereinigung beobachteter Command-Buffer-Intervalle; letzte noch laufende Buffer fehlen. Keine Hardware-Auslastungsmessung.",
                "GPU-Pass-Zeiten werden alle 30 Submits erfasst; Pass-Spannen können überlappen und sind nicht additiv. MetalFX-interne Pässe können ohne Zeitstempel bleiben.",
                "Renderthread-CPU-Zeit zeigt keine Zuordnung zu Performance- oder Effizienzkernen. Bildfehler erfordern Sichtprüfung.",
                "Nässe ist pro Szene fest: Regen 1, sonst 0. So hängt der Vergleich nicht von vorherigen Regenszenen ab.",
                "Ein gemeinsamer Referenzlauf pro Szene erlaubt Feature-Vergleiche; thermische Drift und Reihenfolge können die Differenz beeinflussen."));
            out.put("world",Map.of("name",WORLD,"seed",SEED,"createdThisRun",created));
            out.put("spots",spots==null?Map.of():spots);
            out.put("display",originals.display);out.put("warnings",warnings);out.put("results",results);
            boolean running="läuft".equals(problem);
            Path json=dir.resolve(name+(running?".json.inprogress":".json")),txt=dir.resolve(name+(running?".txt.inprogress":".txt"));
            lastReport=out;
            atomic(json,JSON.toJson(out)+"\n");atomic(txt,BenchReport.text(out));
            if(!running){atomic(dir.resolve(name+".html"),BenchHtml.render(out));Files.deleteIfExists(dir.resolve(name+".json.inprogress"));Files.deleteIfExists(dir.resolve(name+".txt.inprogress"));}
            atomic(dir.resolve(name+".log"),log.toString());
            return json;
        }
        Path spotFile(Minecraft mc) { return mc.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("siliconshaders-benchmark-spots.json"); }
    }

    static void command(MinecraftServer server,String command) {
        server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),command));
    }
    static double round(double value,int digits) { double f=Math.pow(10,digits);return Math.round(value*f)/f; }
    private static void atomic(Path file,String text) throws java.io.IOException {
        Path temporary=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(temporary,text);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }

    /** The player's options, shader settings and window before the run; the benchmark's fixed values in between. */
    private static final class Originals {
        final boolean vsync,bob,pauseOnLostFocus,improvedTransparency,hudHidden,fullscreen;
        final int framerate,renderDistance,simulationDistance,fov,windowWidth,windowHeight;
        final double entityDistance;
        final boolean exclusive;final Optional<VideoMode> preferredMode;
        final CloudStatus clouds;final ParticleStatus particles;
        final SunSettings.State sun;final float[] tuning,camera;final siliconshaders.metal.sun.SunLook.Values look;
        String window="keep";Map<String,Object> display=new LinkedHashMap<>();
        Originals(Minecraft mc) {
            var o=mc.options;
            vsync=o.enableVsync().get();bob=o.bobView().get();pauseOnLostFocus=o.pauseOnLostFocus;improvedTransparency=o.improvedTransparency().get();
            hudHidden=mc.gui.hud.isHidden();fullscreen=o.fullscreen().get();
            framerate=o.framerateLimit().get();renderDistance=o.renderDistance().get();simulationDistance=o.simulationDistance().get();fov=o.fov().get();
            entityDistance=o.entityDistanceScaling().get();clouds=o.cloudStatus().get();particles=o.particles().get();
            windowWidth=mc.getWindow().getScreenWidth();windowHeight=mc.getWindow().getScreenHeight();
            exclusive=o.exclusiveFullscreen().get();preferredMode=mc.getWindow().getPreferredFullscreenVideoMode();
            look=siliconshaders.metal.sun.SunLook.get().values();sun=SunSettings.get().state();tuning=SunTuning.get().values();camera=siliconshaders.metal.camera.CameraSettings.get().snapshot();
        }
        void applyBenchmark(Minecraft mc,BenchPlan.Request request,Run run) {
            var o=mc.options;
            o.enableVsync().set(false);o.framerateLimit().set(260);o.bobView().set(false);o.pauseOnLostFocus=false;o.improvedTransparency().set(false);
            o.renderDistance().set(12);o.simulationDistance().set(8);o.fov().set(70);o.entityDistanceScaling().set(1.0);
            o.cloudStatus().set(CloudStatus.FANCY);o.particles().set(ParticleStatus.ALL);
            if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();
            var w=mc.getWindow();window=request.window();
            if(window.equals("fixed")) {
                if(fullscreen)o.fullscreen().set(false);
                double sx=Math.max(1,w.getWidth()/(double)Math.max(1,w.getScreenWidth())),sy=Math.max(1,w.getHeight()/(double)Math.max(1,w.getScreenHeight()));
                w.setWindowed((int)Math.round(BenchPlan.WINDOW_WIDTH/sx),(int)Math.round(BenchPlan.WINDOW_HEIGHT/sy));
                display.put("targetFramebuffer",BenchPlan.WINDOW_WIDTH+"x"+BenchPlan.WINDOW_HEIGHT);
            } else if(window.equals("fullscreen")&&request.mode().equals(BenchPlan.DESKTOP_MODE)) {
                // Plain fullscreen, no mode change: what a player sees. Not by exact mode: a VideoMode is SDL's w x h without the pixel
                // density, so on a Retina display it isn't the framebuffer. The result records the real framebuffer.
                o.exclusiveFullscreen().set(false);w.setExclusiveFullscreen(false);
                if(!o.fullscreen().get())o.fullscreen().set(true);
                w.changeFullscreenVideoMode();
                display.put("window","fullscreen");display.put("requestedMode",request.mode());display.put("mode",BenchPlan.DESKTOP_MODE);
                Monitor monitor=w.findBestMonitor();if(monitor!=null){display.put("monitor",monitor.name());display.put("monitorMode",String.valueOf(monitor.currentMode()));}
                run.say("Fullscreen at the display's own resolution");
            } else if(window.equals("fullscreen")) {
                // Exact requested exclusive mode, highest supported refresh. Never silently compare another resolution.
                Monitor monitor=w.findBestMonitor();VideoMode best=null;
                if(!request.mode().isEmpty()&&monitor!=null) {
                    String[] want=request.mode().split("x");int ww=Integer.parseInt(want[0]),wh=Integer.parseInt(want[1]);
                    for(VideoMode m:monitor.videoModes())if(m.getWidth()==ww&&m.getHeight()==wh&&(best==null||m.getRefreshRate()>best.getRefreshRate()))best=m;
                    if(best==null)throw new IllegalStateException("Exklusives Vollbild "+request.mode()+" wird von diesem Display nicht angeboten. Verfügbare Modi: "+monitor.videoModes());
                    if(best!=null)w.setPreferredFullscreenVideoMode(Optional.of(best));
                } else if(!request.mode().isEmpty())throw new IllegalStateException("No monitor found for exclusive display mode "+request.mode());
                o.exclusiveFullscreen().set(true);w.setExclusiveFullscreen(true);
                if(!o.fullscreen().get())o.fullscreen().set(true);
                w.changeFullscreenVideoMode();
                display.put("window","fullscreen");display.put("requestedMode",request.mode());
                display.put("mode",best!=null?best.toString():String.valueOf(w.getPreferredFullscreenVideoMode().orElse(null)));
                if(monitor!=null)display.put("monitor",monitor.name());
                run.say("Fullscreen, display mode "+display.get("mode")+(request.mode().isEmpty()?"":" (asked for "+request.mode()+")"));
            } else display.put("window","keep");
        }
        void validateDisplay(Minecraft mc,BenchPlan.Request request){
            if(!request.window().equals("fullscreen"))return;
            var w=mc.getWindow();
            if(request.mode().equals(BenchPlan.DESKTOP_MODE)){
                if(!mc.options.fullscreen().get())throw new IllegalStateException("Fullscreen did not activate");
                return;
            }
            if(!mc.options.fullscreen().get()||!w.isExclusiveFullscreen())throw new IllegalStateException("Exclusive fullscreen did not activate");
            if(!request.mode().isEmpty()){
                String[] want=request.mode().split("x");int width=Integer.parseInt(want[0]),height=Integer.parseInt(want[1]);var mode=w.getActiveVideoMode();
                if(w.getWidth()!=width||w.getHeight()!=height||mode==null||mode.getWidth()!=width||mode.getHeight()!=height)
                    throw new IllegalStateException("Requested exclusive "+request.mode()+", actual framebuffer "+w.getWidth()+"x"+w.getHeight()+", active mode "+mode);
            }
        }
        void applyVariant(BenchPlan.Variant variant) {
            siliconshaders.metal.sun.SunLook.get().preview(BenchPlan.look(variant,look));
            SunSettings.get().benchmark(BenchPlan.state(variant,sun));
            SunTuning.get().benchmark(BenchPlan.tuning(variant,tuning));
            siliconshaders.metal.camera.CameraSettings.get().benchmark(BenchPlan.camera(variant,camera));
            siliconshaders.metal.own.OwnProbe.benchmark(variant.path());
        }
        Map<String,Object> benchmarkOptions() {
            Minecraft mc=Minecraft.getInstance();
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("window",window.equals("fixed")?BenchPlan.WINDOW_WIDTH+"x"+BenchPlan.WINDOW_HEIGHT:window.equals("fullscreen")?"Vollbild "+display.get("mode"):"keep");
            m.put("framebuffer",mc.getWindow().getWidth()+"x"+mc.getWindow().getHeight());
            m.put("renderDistance",12);m.put("simulationDistance",8);m.put("fov",70);m.put("vsync",false);m.put("framerateLimit","unlimited");
            m.put("exclusiveFullscreen",mc.getWindow().isExclusiveFullscreen()&&mc.options.fullscreen().get());m.put("activeVideoMode",String.valueOf(mc.getWindow().getActiveVideoMode()));
            m.put("ambientOcclusion",mc.options.ambientOcclusion().get());m.put("entityShadows",mc.options.entityShadows().get());
            m.put("mipmapLevels",mc.options.mipmapLevels().get());m.put("biomeBlendRadius",mc.options.biomeBlendRadius().get());
            m.put("resourcePacks",new ArrayList<>(mc.options.resourcePacks));
            m.put("clouds","fancy");m.put("particles","all");m.put("graphicsPreset",String.valueOf(mc.options.graphicsPreset().get()));
            m.put("ownSettings",sun);m.put("ownTuning",tuning);m.put("ownLook",look);
            m.put("fixedFramebufferTarget",BenchPlan.WINDOW_WIDTH+"x"+BenchPlan.WINDOW_HEIGHT);
            var cameraValues=new LinkedHashMap<String,Float>();for(int i=0;i<camera.length;i++)cameraValues.put(siliconshaders.metal.camera.CameraSettings.PARAMS.get(i).key(),camera[i]);m.put("ownCameraSettings",cameraValues);
            return m;
        }
        void restore(Minecraft mc) {
            var o=mc.options;
            siliconshaders.metal.sun.SunLook.get().preview(look);SunSettings.get().benchmark(sun);SunTuning.get().benchmark(tuning);siliconshaders.metal.camera.CameraSettings.get().benchmark(camera);
            siliconshaders.metal.own.OwnProbe.benchmark(Map.of());
            o.enableVsync().set(vsync);o.framerateLimit().set(framerate);o.bobView().set(bob);o.pauseOnLostFocus=pauseOnLostFocus;o.improvedTransparency().set(improvedTransparency);
            o.renderDistance().set(renderDistance);o.simulationDistance().set(simulationDistance);o.fov().set(fov);o.entityDistanceScaling().set(entityDistance);
            o.cloudStatus().set(clouds);o.particles().set(particles);
            if(mc.gui.hud.isHidden()!=hudHidden)mc.gui.hud.toggle();
            var w=mc.getWindow();
            if(window.equals("fixed")) { w.setWindowed(windowWidth,windowHeight);if(fullscreen)o.fullscreen().set(true); }
            else if(window.equals("fullscreen")) {
                w.setPreferredFullscreenVideoMode(preferredMode);o.exclusiveFullscreen().set(exclusive);w.setExclusiveFullscreen(exclusive);
                if(!fullscreen) { o.fullscreen().set(false);w.setWindowed(windowWidth,windowHeight); }
                else w.changeFullscreenVideoMode();
            }
        }
    }
}
