package siliconshaders.metal.own;

import java.util.HashMap;
import java.util.Map;
import siliconshaders.metal.Profile;

/**
 * In-run A/B of candidate flags: with -Dsiliconshaders.own.probe=MS, a probe mode
 * (an entry of -Dsiliconshaders.own.probeModes, '/'-separated) may be a set of flag overrides, "key=value" pairs joined by '+', keys without
 * the "siliconshaders.own." prefix (e.g. "frag.splitNear=192+frag.testLate=false"); "base" is the build as configured. A flag on the indie
 * path takes part by reading itself through {@link #bool} / {@link #integer} with its static final as the default: without the probe
 * (ON false, a constant) that is the static final itself, so the default path doesn't change.
 * <p>
 * Registered (switchable at runtime): vignetteNoop (verified black-source HUD vignette only), frag.splitNear (positive values, with a positive static), frag.testLate, frag.msub, frag.fine,
 * frag.testFast, frag.testGather, frag.bRedraw, hizTop, hizGather, frag.pyrSkip (0-2; the pyramid is reallocated when its padding changes),
 * frag.a1Exact (A1's units as exact-count draws, OwnFrag.A1_EXACT), frag.a2Exact (A2's, OwnFrag.A2_EXACT),
 * frag.uocc (false: the frame draws the flags' set without uocc, as -Dsiliconshaders.own.frag.uoccMaxMp does above its size), frag.fogVert (our
 * translucent draw's exact per-vertex fog); measurement only: frag.dropSolidA (OwnFrag.DROP_SOLID_A), frag.aKind (phase A's first solid draw, OwnFrag.aKind), frag.tKind (the
 * translucent draw). To register another: read it through bool / integer at the point it's used each
 * frame, and only if nothing sized or built once depends on it.
 * <p>
 * The benchmark's path variants use the same reads without the mode cycle: {@link Profile#PATH_VARIANTS} is set for one launch by a
 * request that asks for them, and {@link #benchmark} sets the overrides per variant. Also registered for it: frag.uoccSizeSwitch (false:
 * the size cutoff doesn't apply, the frame keeps uocc), frag.vGroupSh, tl.a1Split, sun.receiverGrid.
 */
public final class OwnProbe {
	/** The in-run probe or the benchmark's path variants are on; off this is a constant, and every read below returns its default. */
	static final boolean ON = Long.getLong("siliconshaders.own.probe", 0) > 0 || Boolean.getBoolean(siliconshaders.metal.Profile.PATH_VARIANTS);
	private static volatile Map<String, String> overrides = Map.of();
	/** Render thread: frames seen by the frag cull, of them with uocc / with the a1 split; grouped / ungrouped class draws so far; sun frames, with the grid. */
	private static long fragFrames, uoccFrames, a1Frames, groupedDraws, ungroupedDraws, sunFrames, gridFrames;

	private OwnProbe() {
	}

	/** The benchmark can switch paths: asked for at launch, and no mode cycle (-Dsiliconshaders.own.probe) that would replace its overrides. */
	public static boolean pathVariantsAvailable() {
		return ON && Long.getLong("siliconshaders.own.probe", 0) <= 0;
	}

	/** The benchmark's overrides from now on (empty: the build as configured). */
	public static void benchmark(Map<String, String> paths) {
		overrides = Map.copyOf(paths);
	}

	static void noteFrag(boolean uocc, boolean a1Split, long grouped, long ungrouped) {
		fragFrames++;
		if (uocc) uoccFrames++;
		if (a1Split) a1Frames++;
		groupedDraws = grouped;
		ungroupedDraws = ungrouped;
	}

	static void noteSun(boolean grid) {
		sunFrames++;
		if (grid) gridFrames++;
	}

	/** The counters as of now, for {@link #effective} over a measurement. */
	public static long[] counters() {
		return new long[] {fragFrames, uoccFrames, a1Frames, groupedDraws, ungroupedDraws, sunFrames, gridFrames};
	}

	/** What the frames between two counters really drew: the check that a path variant took effect. */
	public static Map<String, Object> effective(long[] from, long[] to) {
		long frag = to[0] - from[0], sun = to[5] - from[5];
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("fragFrames", frag);
		out.put("uoccShare", frag > 0 ? (to[1] - from[1]) / (double) frag : 0);
		out.put("a1SplitShare", frag > 0 ? (to[2] - from[2]) / (double) frag : 0);
		out.put("groupedDraws", to[3] - from[3]);
		out.put("ungroupedDraws", to[4] - from[4]);
		out.put("sunFrames", sun);
		out.put("receiverGridShare", sun > 0 ? (to[6] - from[6]) / (double) sun : 0);
		return out;
	}

	/** This frame's overrides from probe mode m: true if m is a flag-override mode or "base" (the rest of the probe treats it as "draw"). */
	static boolean select(String m) {
		if (m.equals("base")) {
			overrides = Map.of();
			return true;
		}
		if (!m.contains("=")) {
			overrides = Map.of();
			return false;
		}
		Map<String, String> o = new HashMap<>();
		for (String kv : m.split("\\+")) {
			int i = kv.indexOf('=');
			if (i > 0) o.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
		}
		overrides = o;
		return true;
	}


	public static boolean bool(String key, boolean dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : Boolean.parseBoolean(v);
	}

	static String string(String key, String dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : v;
	}

	static int integer(String key, int dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : Integer.parseInt(v);
	}
}
