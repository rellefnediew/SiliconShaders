package siliconshaders.metal;

/**
 * Benchmark only (bench.ShaderBench): the GPU's busy time while a measurement runs, from each command buffer's start and
 * end time as it retires. Overlapping buffers count once (the union of their intervals), so busy / wall time is the GPU's
 * utilization. Off, retire pays one volatile read.
 */
public final class BenchGpu {
	static volatile boolean on;
	private static double busy, lastEnd;
	private static long buffers, generation, waitNanos;
    private static final java.util.Map<String,double[]> stages=new java.util.LinkedHashMap<>();
    public static long epoch(){return on?generation:0;}
    public static boolean sample(long submit){return on&&submit>0&&submit%30==0;}
    public static synchronized void stage(long epoch,String label,double ms){
        if(!on||epoch!=generation||!Double.isFinite(ms)||ms<0)return;
        double[] value=stages.computeIfAbsent(label,k->new double[2]);value[0]+=ms;value[1]++;
    }
    public static synchronized java.util.Map<String,Object> details(){
        var result=new java.util.LinkedHashMap<String,Object>();var labels=new java.util.LinkedHashMap<String,Object>();
        stages.forEach((k,v)->labels.put(k,java.util.Map.of("totalMs",v[0],"occurrences",(long)v[1],"meanMs",v[0]/v[1])));
        result.put("stages",labels);result.put("commandWaitMs",waitNanos/1e6);return result;
    }

	private BenchGpu() {}

	public static synchronized void start() {
		busy = 0;
		lastEnd = 0;
		buffers = 0;
        generation++;waitNanos=0;stages.clear();
		on = true;
	}

	/** Stops and returns {busy seconds, command buffers}. */
	public static synchronized double[] stop() {
		on = false;
		return new double[] {busy, buffers};
	}

	static synchronized void retire(long cmd,long epoch,long waited) {
		if (!on||epoch!=generation) return;
        waitNanos+=Math.max(0,waited);
		double start = Native.cmdGpuStart(cmd), end = Native.cmdGpuEnd(cmd);
		if (!(start > 0) || !(end > start)) return;
		buffers++;
		busy += union(start, end, lastEnd);
		lastEnd = Math.max(lastEnd, end);
	}

	/** The part of [start, end] after everything counted up to previousEnd. */
	public static double union(double start, double end, double previousEnd) {
		return end <= previousEnd ? 0 : end - Math.max(start, previousEnd);
	}
}
