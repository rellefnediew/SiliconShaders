package siliconshaders.metal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Pure policy checks; does not initialize Minecraft, SDL, Metal or a video device. */
public final class GpuTuningChecks {
    private static void require(boolean yes,String message) { if(!yes)throw new AssertionError(message); }
    private static GpuTuning.Selection chip(String name,int cores) { return GpuTuning.select(new GpuTuning.Hardware(name,cores)); }
    public static void main(String[] args) {
        int cases=0;
        for(int generation=1;generation<=6;generation++)for(String variant:new String[]{""," Pro"," Max"," Ultra"}) {
            for(int count:new int[]{-1,8,10,14,40}) {
                var policy=chip("Apple M"+generation+variant,count);
                require(!policy.name().equals("unknown GPU"),"Known chip lost its policy");
                require(policy.group()==0||policy.group()==2,"Unsupported grouping");
                require(policy.split()==0||policy.split()==30,"Unexpected split");
                require(!policy.reserve(),"Unmeasured M-series memory reservation");
                var values=policy.defaults();
                require(values.get("siliconshaders.own.frag.vGroupMinCores").equals("0"),"Automatic chip choice retained the old grouping gate");
                require(values.get("siliconshaders.own.tl.a1SplitMinCores").equals("0")&&values.get("siliconshaders.own.tl.a1SplitMaxCores").equals("0"),"Automatic split retained core gates");
                cases++;
            }
        }
        require(chip("Apple M1 Pro",14).group()==2&&chip("Apple M1 Pro",14).split()==30,"M1 Pro baseline changed");
        require(chip("Apple M4",10).group()==0&&chip("Apple M4",10).split()==0,"M4 base measured baseline changed");
        require(chip("Apple M4 Max",40).occlusionCutoff()==6,"M4 Max high-resolution baseline changed");
        require(chip("Apple M6",12).group()==2&&chip("Apple M6",12).split()==30,"M6 base baseline changed");
        for(int cores:new int[]{8,10,-1}) {
            require(chip("Apple M5",cores).group()==2,"M5 fast path still depends on having twelve cores");
            require(!chip("Apple M5",cores).receiverCulling(),"M5 base retained the reported full-screen culling regression");
        }
        require(chip("Apple M1 Pro",14).receiverCulling()&&chip("Apple M5 Pro",-1).receiverCulling(),"Unreported GPUs changed shadow culling policy");
        require(chip("Apple M1",8).group()!=chip("Apple M5",8).group(),"Same core count overrode different chip identities");
        require(chip("Apple A18 Pro",5).exact()&&chip("Apple A18 Pro",5).reserve()&&!chip("Apple A18 Pro",5).atlasWrite(),"A18 Pro measured paths changed");
        require(!chip("Apple A18 Pro",-1).reserve(),"Unknown capacity enabled memory reservation");
        for(String model:new String[]{"", "Apple M70 Max", "AMD Radeon", "Apple M5 typo", "Apple M999999999999999999"}) {
            var policy=chip(model,40);
            require(policy.group()==0&&policy.split()==0&&!policy.atlasWrite()&&!policy.reserve()&&!policy.exact(),"Unknown hardware inherited high-core paths");
        }
        var found=GpuTuning.parseHardware("\"model\" = \"Apple M5\"\n\"gpu-core-count\" = 8","Apple M1 Pro");
        require(found.model().equals("Apple M5")&&found.cores()==8,"Registry GPU identity not authoritative");
        found=GpuTuning.parseHardware("\"gpu-core-count\" = 10","Apple M5");
        require(found.model().equals("Apple M5")&&found.cores()==10,"CPU-name fallback lost chip identity");
        found=GpuTuning.parseHardware("\"model\" = \"AMD Radeon\"","Apple M5");
        require(chip(found.model(),found.cores()).name().equals("unknown GPU"),"Foreign GPU adopted the CPU's Apple policy");
        found=GpuTuning.parseHardware("\"model\" = \"Apple GPU\"\n\"gpu-core-count\" = 99999999999999999999","Apple M5");
        require(found.model().equals("Apple M5")&&found.cores()==-1,"Malformed registry did not fall back safely");
        Map<String,String> flags=new HashMap<>();
        GpuTuning.applyDefaults(flags,Set.of(),chip("Apple M5",8));
        require(flags.get("siliconshaders.sun.receiverCulling").equals("false"),"M5 receiver policy did not enter launch flags");
        require(flags.get("siliconshaders.own.frag.vGroup").equals("2")&&flags.get("siliconshaders.own.frag.vGroupMinCores").equals("0"),"M5 defaults did not enter launch flags");
        flags.put("siliconshaders.own.frag.vGroup","8");
        flags.put("siliconshaders.own.frag.vGroupMinCores","12");
        GpuTuning.applyDefaults(flags,Set.of("siliconshaders.own.frag.vGroup","siliconshaders.own.frag.vGroupMinCores"),chip("Apple M5",8));
        require(flags.get("siliconshaders.own.frag.vGroup").equals("8")&&flags.get("siliconshaders.own.frag.vGroupMinCores").equals("12"),"Explicit file settings were overridden");
        flags.put("siliconshaders.sun.receiverCulling","true");
        GpuTuning.applyDefaults(flags,Set.of("siliconshaders.sun.receiverCulling"),chip("Apple M5",8));
        require(flags.get("siliconshaders.sun.receiverCulling").equals("true"),"Manual receiver-grid override was ignored");
        String key="siliconshaders.own.frag.vGroup",previous=System.getProperty(key);
        try {
            System.setProperty(key,"4");flags=new HashMap<>();
            GpuTuning.applyDefaults(flags,Set.of(),chip("Apple M5",8));
            require(!flags.containsKey(key)&&System.getProperty(key).equals("4"),"JVM setting was replaced by automatic tuning");
        } finally { if(previous==null)System.clearProperty(key);else System.setProperty(key,previous); }
        var host=GpuTuning.hardware();
        System.out.println("Detected host: "+host.model()+", GPU cores="+host.cores()+", policy="+GpuTuning.select(host).name());
        System.out.println("PASS: "+cases+" chip/variant/core-count cases, M5 8/10-core paths, known baselines, registry fallback, unknown hardware and explicit setting precedence");
    }
}
