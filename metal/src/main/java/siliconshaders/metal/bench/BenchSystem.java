package siliconshaders.metal.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The Mac a benchmark ran on: chip, GPU cores, memory, macOS, power source, and over the run the thermal state and Low
 * Power Mode (NSProcessInfo through the Objective-C runtime, so no native rebuild). Every probe fails soft (-1 or "").
 */
final class BenchSystem {
    private BenchSystem() {}
    static final String[] THERMAL={"normal","leicht","hoch","kritisch"};

    /** Static facts, read once at the start of a run (a few short processes). */
    static Map<String,Object> describe() {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("chip",run("sysctl","-n","machdep.cpu.brand_string").trim());
        m.put("model",run("sysctl","-n","hw.model").trim());
        String memory=run("sysctl","-n","hw.memsize").trim();
        m.put("memoryGB",memory.matches("\\d+")?Math.round(Long.parseLong(memory)/1073741824.0):-1);
        Matcher cores=Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(run("ioreg","-rc","AGXAccelerator","-d1"));
        m.put("gpuCores",cores.find()?Integer.parseInt(cores.group(1)):-1);
        m.put("macos",System.getProperty("os.version"));
        m.put("java",System.getProperty("java.version"));
        m.put("architecture",System.getProperty("os.arch"));
        m.put("cpuCores",run("sysctl","-n","hw.physicalcpu").trim());
        m.put("performanceCores",run("sysctl","-n","hw.perflevel0.physicalcpu").trim());
        m.put("efficiencyCores",run("sysctl","-n","hw.perflevel1.physicalcpu").trim());
        m.put("javaVendor",System.getProperty("java.vendor"));
        m.put("heapMaxMB",Runtime.getRuntime().maxMemory()/1048576);
        m.put("minecraft",net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").map(c->c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        var mods=new java.util.TreeMap<String,String>();
        net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().forEach(c->mods.put(c.getMetadata().getId(),c.getMetadata().getVersion().getFriendlyString()));m.put("mods",mods);
        var overrides=new java.util.TreeMap<String,String>();System.getProperties().forEach((k,v)->{if(k.toString().startsWith("siliconshaders."))overrides.put(k.toString(),v.toString());});m.put("siliconshadersProperties",overrides);
        String battery=run("pmset","-g","batt");
        m.put("power",battery.contains("AC Power")?"Netzteil":battery.contains("Battery Power")?"Akku":"");
        m.put("lowPower",lowPower());
        m.put("thermal",thermal());
        // The startup GPU path policy (GpuTuning) and receiver grid: they differ between chips, so a comparison shows them.
        try {
            var policy=siliconshaders.metal.GpuTuning.select(siliconshaders.metal.GpuTuning.hardware());
            m.put("gpuPolicy",policy.name());m.put("gpuPolicyBasis",policy.basis());m.put("gpuPolicyDefaults",new java.util.TreeMap<>(policy.defaults()));
        } catch(Throwable failure) { m.put("gpuPolicy","?"); }
        m.put("receiverCulling",siliconshaders.metal.sun.SunSurfaceFrame.RECEIVER_CULLING);
        m.put("smartLeaves",siliconshaders.metal.fast.SmartLeaves.get().mode().name());
        m.put("lodEnabled",siliconshaders.metal.lod.LodConfig.ENABLED);
        return m;
    }
    private static String run(String... command) {
        try {
            Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] out=p.getInputStream().readNBytes(1<<20);
            if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();
            return new String(out,StandardCharsets.UTF_8);
        } catch(Exception failure) { return ""; }
    }

    private static MethodHandle getClass,selector,sendPointer,sendLong,sendBool;
    private static MemorySegment processInfo,thermalState,lowPowerMode;
    private static boolean failed;
    /** NSProcessInfo.thermalState: 0 nominal, 1 fair, 2 serious, 3 critical; -1 unknown. */
    static synchronized int thermal() {
        try { return link()?(int)(long)sendLong.invokeExact(processInfo,thermalState):-1; } catch(Throwable failure) { failed=true;return -1; }
    }
    /** NSProcessInfo.isLowPowerModeEnabled; null unknown. */
    static synchronized Boolean lowPower() {
        try { return link()?(byte)sendBool.invokeExact(processInfo,lowPowerMode)!=0:null; } catch(Throwable failure) { failed=true;return null; }
    }
    private static MemorySegment sel(String name) throws Throwable {
        return (MemorySegment)selector.invokeExact(Arena.global().allocateFrom(name));
    }
    private static boolean link() throws Throwable {
        if(failed)return false;
        if(processInfo!=null)return true;
        Linker linker=Linker.nativeLinker();
        SymbolLookup objc=SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib",Arena.global());  // by name: it lives in the shared cache, not on disk
        MemorySegment send=objc.find("objc_msgSend").orElseThrow();
        getClass=linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),FunctionDescriptor.of(ADDRESS,ADDRESS));
        selector=linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),FunctionDescriptor.of(ADDRESS,ADDRESS));
        sendPointer=linker.downcallHandle(send,FunctionDescriptor.of(ADDRESS,ADDRESS,ADDRESS));
        sendLong=linker.downcallHandle(send,FunctionDescriptor.of(JAVA_LONG,ADDRESS,ADDRESS));
        sendBool=linker.downcallHandle(send,FunctionDescriptor.of(JAVA_BYTE,ADDRESS,ADDRESS));
        MemorySegment type=(MemorySegment)getClass.invokeExact(Arena.global().allocateFrom("NSProcessInfo"));
        if(type.address()==0){failed=true;return false;}
        thermalState=sel("thermalState");lowPowerMode=sel("isLowPowerModeEnabled");
        processInfo=(MemorySegment)sendPointer.invokeExact(type,sel("processInfo"));
        if(processInfo.address()==0){processInfo=null;failed=true;return false;}
        return true;
    }
}
