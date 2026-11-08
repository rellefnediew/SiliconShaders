package siliconshaders.metal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Chip-specific defaults for equivalent renderer paths; never changes shader quality. */
public final class GpuTuning {
    public record Hardware(String model,int cores) {
        public Hardware { model=model==null?"":model.strip(); }
    }
    public record Selection(String name,String basis,int group,int split,boolean exact,
                            boolean atlasWrite,boolean reserve,double occlusionCutoff,boolean receiverCulling) {
        public Map<String,String> defaults() {
            Map<String,String> result=new LinkedHashMap<>();
            result.put("siliconshaders.own.frag.vGroup",Integer.toString(group));
            result.put("siliconshaders.own.frag.vGroupMinCores","0");
            result.put("siliconshaders.own.tl.a1Split",Integer.toString(split));
            result.put("siliconshaders.own.tl.a1SplitMinCores","0");
            result.put("siliconshaders.own.tl.a1SplitMaxCores","0");
            result.put("siliconshaders.own.frag.a2Exact",Boolean.toString(exact));
            result.put("siliconshaders.own.frag.a2ExactMaxCores","0");
            result.put("siliconshaders.own.int.atlasWrite",Boolean.toString(atlasWrite));
            result.put("siliconshaders.own.int.animOnePass",Boolean.toString(atlasWrite));
            result.put("siliconshaders.own.int.animMinCores","0");
            result.put("siliconshaders.own.arena.preSize",Boolean.toString(reserve));
            result.put("siliconshaders.own.arena.preSizeMaxCores","8");
            result.put("siliconshaders.own.frag.uoccMaxMp",Double.toString(occlusionCutoff));
            result.put("siliconshaders.own.frag.uoccMaxMpCores","0");
            result.put("siliconshaders.sun.receiverCulling",Boolean.toString(receiverCulling));
            return Map.copyOf(result);
        }
    }
    private static final Pattern M=Pattern.compile("Apple M([1-9][0-9]*)(?: (Pro|Max|Ultra))?",Pattern.CASE_INSENSITIVE);
    private GpuTuning() {}

    /** Pure selection: a new low-core chip is never mistaken for an older small GPU. */
    public static Selection select(Hardware hardware) {
        String model=hardware.model();
        if(model.equalsIgnoreCase("Apple A18 Pro"))
            return new Selection("A18 Pro","existing five-core baseline",0,30,true,false,hardware.cores()==5,0,true);
        var match=M.matcher(model);
        if(!match.matches())return fallback();
        int generation;
        try { generation=Integer.parseInt(match.group(1)); } catch(NumberFormatException e) { return fallback(); }
        String variant=match.group(2)==null?"base":match.group(2).toLowerCase(Locale.ROOT);
        boolean large=!variant.equals("base");
        if(generation>=1&&generation<=4) {
            // Carry the legacy base/large-GPU defaults by identity; unknown capacity
            // no longer disables the selected path. Small M-series variants use no reserve.
            return new Selection("M"+generation+" "+variant,"existing compatible baseline",
                large?2:0,large?30:0,false,true,false,large?6:0,true);
        }
        if(generation==5) {
            // Provisional: grouped draws remain valid at 8/10 cores. M5 measurements
            // are still required before claiming a speed gain or changing pass splitting.
            // The base M5 has a reported culling regression: omit the full-screen
            // receiver grid while retaining frustum culling and deferred lighting.
            return new Selection("M5 "+variant,"provisional M5 defaults",
                2,large?30:0,false,true,false,large?6:0,large);
        }
        if(generation==6) {
            return new Selection("M6 "+variant,large?"inherited Pro-family defaults":"existing twelve-core baseline",
                2,30,false,true,false,6,true);
        }
        // Unknown future generations do not inherit a policy just because core counts match.
        return fallback();
    }
    private static Selection fallback() {
        return new Selection("unknown GPU","conservative fallback",0,0,false,false,false,0,false);
    }

    /** Configuration-file and command-line values remain explicit user overrides. */
    static void applyDefaults(Map<String,String> flags,Set<String> fileOverrides,Selection selection) {
        selection.defaults().forEach((key,value)->{
            if(!fileOverrides.contains(key)&&System.getProperty(key)==null)flags.put(key,value);
        });
    }

    private static final class Detected { static final Hardware VALUE=detect(); }
    public static Hardware hardware() { return Detected.VALUE; }
    static Hardware parseHardware(String registry,String cpu) {
        var cores=Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(registry);
        int count=-1;
        if(cores.find())try { count=Integer.parseInt(cores.group(1)); } catch(NumberFormatException ignored) {}
        var model=Pattern.compile("\"model\"\\s*=\\s*\"([^\"]+)\"").matcher(registry);
        String name=model.find()?model.group(1).strip():"";
        if(name.isEmpty()||name.equalsIgnoreCase("Apple GPU")||name.startsWith("AGX"))name=cpu.strip();
        return new Hardware(name,count);
    }
    private static Hardware detect() {
        if(!System.getProperty("os.name","").startsWith("Mac"))return new Hardware("",-1);
        return parseHardware(run("/usr/sbin/ioreg","-rd1","-c","AGXAccelerator"),
            run("/usr/sbin/sysctl","-n","machdep.cpu.brand_string"));
    }
    private static String run(String... command) {
        Process process=null;
        try {
            process=new ProcessBuilder(command).redirectErrorStream(true).start();
            // Read concurrently so a large IORegistry reply cannot block the process
            // before its timeout. Bound retained output; always close the reader.
            final Process current=process;
            var read=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try(var input=current.getInputStream()) {
                    byte[] bytes=input.readNBytes(65536);input.transferTo(java.io.OutputStream.nullOutputStream());
                    return new String(bytes,StandardCharsets.UTF_8);
                } catch(Exception e) { return ""; }
            });
            if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();return "";}
            return process.exitValue()==0?read.get(1,TimeUnit.SECONDS):"";
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();return "";
        } catch(Exception e) { return ""; }
        finally { if(process!=null&&process.isAlive())process.destroyForcibly(); }
    }
}
