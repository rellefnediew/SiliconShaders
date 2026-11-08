package siliconshaders.metal.bench;

import java.util.Arrays;

/** One measurement's numbers from its frame times (ns between frame starts) and the GPU's busy time. */
public record BenchStats(int frames,double seconds,double fps,double low1,double p50,double p95,double p99,double max,double gpuMs,double gpuBusy) {
    /**
     * fps: frames / wall time. low1: the "1% low", fps over the slowest 1% of frames (at least one). p50/p95/p99/max:
     * frame times in ms. gpuMs: GPU busy time per frame; gpuBusy: the share of the wall time the GPU was busy (0-1).
     */
    public static BenchStats of(long[] frameNanos,int count,double wallSeconds,double gpuSeconds) {
        if(count<=0||!(wallSeconds>0))return new BenchStats(0,Math.max(0,wallSeconds),0,0,0,0,0,0,0,0);
        double[] ms=new double[count];
        for(int i=0;i<count;i++)ms[i]=frameNanos[i]/1e6;
        Arrays.sort(ms);
        int worst=Math.max(1,count/100);double slow=0;
        for(int i=count-worst;i<count;i++)slow+=ms[i];
        return new BenchStats(count,wallSeconds,count/wallSeconds,1000/(slow/worst),
            percentile(ms,0.50),percentile(ms,0.95),percentile(ms,0.99),ms[count-1],gpuSeconds*1000/count,Math.min(1,gpuSeconds/wallSeconds));
    }
    /** Nearest-rank percentile of sorted values. */
    static double percentile(double[] sorted,double p) {
        return sorted[Math.clamp((int)Math.ceil(p*sorted.length)-1,0,sorted.length-1)];
    }
}
