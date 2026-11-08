package siliconshaders.metal.cpu;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/** Scratch arrays and the merge for DrawMergeMixin (render thread only). */
public final class DrawMerge {
	private static DrawMerge instance;
	private int capacity;
	public long pointers, counts, bases;
	private static long ranges, draws, lastPrint = System.nanoTime();

	private DrawMerge() {
	}



	private void put(int i, long p, long c, long b) {
		MemoryUtil.memPutAddress(this.pointers + (long) i * Pointer.POINTER_SIZE, p);
		MemoryUtil.memPutInt(this.counts + 4L * i, (int) c);
		MemoryUtil.memPutInt(this.bases + 4L * i, (int) b);
	}

	private static void stats(int in, int out) {
		ranges += in;
		draws += out;
		long now = System.nanoTime();
		if (now - lastPrint > 5_000_000_000L) {
			lastPrint = now;
			siliconshaders.metal.Log.infof("siliconshaders-cpu: mergeDraws ranges %d -> draws %d (%.1f%%)%n", ranges, draws, 100.0 * draws / Math.max(1, ranges));
		}
	}
}
