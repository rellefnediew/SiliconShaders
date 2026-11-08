package siliconshaders.metal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in, render-thread-only cadence recorder. No Java allocation on submit/present/retire.
 * Fixed capacity never wraps: overflow is reported rather than silently losing early phases.
 * Native presented callbacks write separate atomic slots and never call Java.
 */
public final class GpuTimes {
	private static final int CAPACITY = 1 << 20;
	private static final long[] submitNs = new long[CAPACITY], startNs = new long[CAPACITY], endNs = new long[CAPACITY];
	private static final boolean[] presenting = new boolean[CAPACITY];
	/** Render encoders the frame opened (vanilla's texture-animation tick frames open one per atlas mip). */
	private static final int[] passes = new int[CAPACITY];
	private static final long[] pollNs = Latency.ON ? new long[CAPACITY] : null;
	private static final long[] calibration = calibrate();
	private static int retired;
	private static long dropped;

	private GpuTimes() {}
	static void initialize() {} // initialize storage and clock mapping before recording

	/** Bracket the native clock with nanoTime, retaining the narrowest of 64 brackets.
	 * [Java midpoint - native ns, bracket width, Java midpoint]. Snapshot repeats this
	 * so reports can test both offset and drift instead of assuming identical origins.
	 */
	private static long[] calibrate() {
		long best = Long.MAX_VALUE, offset = 0, midpoint = 0;
		for (int i = 0; i < 64; i++) {
			long a = System.nanoTime();
			long host = Math.round(Native.hostSeconds() * 1e9);
			long b = System.nanoTime();
			if (b - a < best) { best = b - a; midpoint = a + best / 2; offset = midpoint - host; }
		}
		return new long[] {offset, best, midpoint};
	}

	static void submit(long index, int renderPasses) {
		if (index >= CAPACITY) return;
		submitNs[(int) index] = System.nanoTime();
		passes[(int) index] = renderPasses;
		if (probeNames != null) {
			probeModeOf[(int) index] = probeMode;
			probeWindowOf[(int) index] = probeWindow;
		}
	}

	/** The in-run probe (OwnTerrain, -Dsiliconshaders.own.probe): its current mode and window, tagged on each submit from now on. */
	private static volatile int probeMode = -1, probeWindow = -1;
	private static volatile String[] probeNames;
	private static int[] probeModeOf, probeWindowOf;

	public static void probeTag(int mode, int window, String[] names) {
		if (probeNames == null) {
			probeModeOf = new int[CAPACITY];
			probeWindowOf = new int[CAPACITY];
			java.util.Arrays.fill(probeModeOf, -1);
			java.util.Arrays.fill(probeWindowOf, -1);
		}
		probeMode = mode;
		probeWindow = window;
		probeNames = names;
	}
	static void present(long index, long drawable) {
		if (index >= CAPACITY) return;
		presenting[(int) index] = true;
		if (pollNs != null) pollNs[(int) index] = Latency.lastPollNs;
		Native.cadencePresent(drawable, (int) index);
	}
	/** A present whose drawable is acquired on the present side (presentQueue=acquire): native registers the scanout probe. */
	static void presentQueued(long index) {
		if (index >= CAPACITY) return;
		presenting[(int) index] = true;
		if (pollNs != null) pollNs[(int) index] = Latency.lastPollNs;
	}
	static void retire(long index, long cmd) {
		if (index >= CAPACITY) { dropped++; return; }
		int i = (int) index;
		double start = Native.cmdGpuStart(cmd), end = Native.cmdGpuEnd(cmd);
		startNs[i] = start > 0 ? Math.round(start * 1e9) + calibration[0] : 0;
		endNs[i] = end > 0 ? Math.round(end * 1e9) + calibration[0] : 0;
		retired = i + 1;
	}



	private static double low(long[] sorted, double fraction) {
		int n = Math.max(1, (int) (sorted.length * fraction));
		long sum = 0;
		for (int i = sorted.length - n; i < sorted.length; i++) sum += sorted[i];
		return n * 1e9 / sum;
	}
}
