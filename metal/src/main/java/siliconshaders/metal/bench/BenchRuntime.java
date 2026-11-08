package siliconshaders.metal.bench;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread CPU time and JVM activity. CPU time excludes blocking; it cannot identify P/E-core placement. */
final class BenchRuntime {
    record Sample(long cpu,long gcCount,long gcMs,long compileMs,long heap){}
    static Sample sample(){
        var threads=ManagementFactory.getThreadMXBean();long cpu=threads.isCurrentThreadCpuTimeSupported()?threads.getCurrentThreadCpuTime():-1;
        long count=0,time=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){count+=Math.max(0,gc.getCollectionCount());time+=Math.max(0,gc.getCollectionTime());}
        var compiler=ManagementFactory.getCompilationMXBean();long compile=compiler!=null&&compiler.isCompilationTimeMonitoringSupported()?compiler.getTotalCompilationTime():-1;
        return new Sample(cpu,count,time,compile,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }
    static Map<String,Object> between(Sample start,Sample end,int frames){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("renderThreadCpuMs",start.cpu>=0&&end.cpu>=start.cpu?(end.cpu-start.cpu)/1e6:null);
        out.put("renderThreadCpuMsPerFrame",start.cpu>=0&&end.cpu>=start.cpu&&frames>0?(end.cpu-start.cpu)/1e6/frames:null);
        out.put("gcCollections",end.gcCount-start.gcCount);out.put("gcMs",end.gcMs-start.gcMs);
        out.put("jitMs",start.compileMs>=0&&end.compileMs>=0?end.compileMs-start.compileMs:null);
        out.put("heapStartMB",start.heap/1048576);out.put("heapEndMB",end.heap/1048576);return out;
    }
}
