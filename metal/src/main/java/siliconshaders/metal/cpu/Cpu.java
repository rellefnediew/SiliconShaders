package siliconshaders.metal.cpu;

import java.lang.reflect.Field;

/**
 * Opt-in CPU-side render-loop work. Every lever here is off unless its -Dsiliconshaders.cpu.* property names it, and with
 * none set none of the mixins in siliconshaders.metal.mixin.cpu apply, so the game is unchanged.
 *
 * <ul>
 * <li>{@code -Dsiliconshaders.cpu.cullReuse=true}: Sodium's async cull is skipped on a frame whose cull could only rebuild the trees
 * already in use (see {@link CullReuse}).</li>
 * <li>{@code -Dsiliconshaders.cpu.cullReuse=verify}: Sodium culls as it always does; each cull the rule would have skipped is compared,
 * bit for bit, against the trees the skip would have kept. Mismatches are counted and logged.</li>
 * <li>{@code -Dsiliconshaders.cpu.loadingFps=N}: the world-loading screen (shown while the level already exists, so vanilla's 60 fps menu
 * cap doesn't apply and it renders uncapped) at most N fps.</li>
 * <li>{@code -Dsiliconshaders.cpu.cullStats=true}: per bench phase, frames, culls, skips and the rule's reasons on stdout.</li>
 * </ul>
 */
public final class Cpu {
	private static final String CULL_REUSE = System.getProperty("siliconshaders.cpu.cullReuse", "");
	public static final boolean CULL_SKIP = CULL_REUSE.equals("true");
	public static final boolean CULL_VERIFY = CULL_REUSE.equals("verify");
	public static final boolean CULL_STATS = Boolean.getBoolean("siliconshaders.cpu.cullStats") || CULL_VERIFY;
	/** -Dsiliconshaders.cpu.lists=true: render-list walk micro-work (region lookups cached per walk). */
	public static final boolean LISTS_AB = "ab".equals(System.getProperty("siliconshaders.cpu.lists"));
	public static final boolean LISTS = Boolean.getBoolean("siliconshaders.cpu.lists") || LISTS_AB;
	/** Whether the region cache is on for the current walk (always, unless lists=ab alternates it). */
	public static boolean listsActive = true;
	private static long recN0, recN1, recNs0, recNs1, recFrames0, recFrames1, recLastFrame = -1, recLastPrint = System.nanoTime();


	/**
	 * -Dsiliconshaders.cpu.cullRecover=true: Sodium resets its graph-dirty flag when it schedules a cull, and cancels a scheduled cull that
	 * hasn't started by the next frame; if the camera then holds still and nothing else dirties the graph, the update is lost until
	 * something does. With this, a cancelled cull leaves the graph marked dirty, so the next frame schedules it again.
	 */
	public static final boolean CULL_RECOVER = Boolean.getBoolean("siliconshaders.cpu.cullRecover");
	/** -Dsiliconshaders.cpu.model=true|ab: ModelPart.compile with an indexed cube loop. */
	public static final boolean MODEL_AB = "ab".equals(System.getProperty("siliconshaders.cpu.model"));
	public static final boolean MODEL = Boolean.getBoolean("siliconshaders.cpu.model") || MODEL_AB;
	public static boolean modelActive = true;
	/** -Dsiliconshaders.cpu.mergeDraws=true|ab|stats: contiguous Sodium terrain ranges merged before the multi-draw (DrawMergeMixin). */
	private static final String MERGE = System.getProperty("siliconshaders.cpu.mergeDraws", "");
	public static final boolean MERGE_AB = MERGE.equals("ab");
	public static final boolean MERGE_STATS = MERGE.equals("stats") || Boolean.getBoolean("siliconshaders.cpu.mergeStats");
	public static final boolean MERGE_ON = MERGE.equals("true") || MERGE_AB || MERGE_STATS;
	public static boolean mergeActive = MERGE_ON;
	/** MetalTerrain's per-frame choice: Sodium's solid/cutout terrain is recorded for the occlusion split (set at each frame's end). */
	public static volatile boolean terrainSplitting = true;
	/** -Dsiliconshaders.cpu.abShots=texel|model: same-run picture check (AbShots). */
	public static final boolean AB_SHOTS = !System.getProperty("siliconshaders.cpu.abShots", "").isEmpty();
	public static final boolean LEASH = false;
	public static boolean leashActive = true;
	public static final int LOADING_FPS = Integer.getInteger("siliconshaders.cpu.loadingFps", 0);

	/** mc_cpu_flags' bits, with the pass lever as given. */
	public static int nativeFlags(boolean pass) {
		return (pass ? 1 : 0) | ((0));
	}

	/** pass=ab: time in the native pass begin, lever on odd frames. */
	public static final Ab PASS_TIMER = (null);
	/**
	 * -Dsiliconshaders.cpu.fence=true|stats: MetalEncoder.createFence right after a submit, with nothing recorded since, names that submit
	 * rather than the next one (see there). stats: fences made and moved, render-thread time blocked in GpuFence.awaitCompletion
	 * per frame, every 5 s, without the change; true,stats: with it.
	 */
	private static final String FENCE_PROP = System.getProperty("siliconshaders.cpu.fence", "");
	public static final boolean FENCE = FENCE_PROP.equals("true") || FENCE_PROP.equals("true,stats");
	public static final boolean FENCE_STATS = FENCE_PROP.endsWith("stats");
	public static boolean fenceActive = true;
	private static long fenceMade, fenceMoved, fenceWaits, fenceWaitNs, fenceFirstSubmit = -1, fenceLastPrint = System.nanoTime();

	public static void fenceMade(boolean moved) {
		fenceMade++;
		if (moved) fenceMoved++;
	}

	public static void fenceWaited(long ns) {
		fenceWaits++;
		fenceWaitNs += ns;
	}

	/** Each submit (stats only). */
	public static void fenceTick(long submit) {
		if (fenceFirstSubmit < 0) fenceFirstSubmit = submit;
		long now = System.nanoTime();
		if (now - fenceLastPrint > 5_000_000_000L && submit > fenceFirstSubmit) {
			double frames = submit - fenceFirstSubmit;
			siliconshaders.metal.Log.infof("siliconshaders-cpu: fence %s made %d moved %d, blocking waits %.3f/frame, blocked %.1f us/frame (%s)%n", FENCE ? "on" : "off",
				fenceMade, fenceMoved, fenceWaits / frames, fenceWaitNs / 1e3 / frames, phase());
			fenceLastPrint = now;
			fenceMade = fenceMoved = fenceWaits = fenceWaitNs = 0;
			fenceFirstSubmit = submit;
		}
	}

	private Cpu() {
	}

	public static boolean cullHooks() {
		return CULL_SKIP || CULL_VERIFY || CULL_STATS || CULL_RECOVER;
	}

	private static Field benchPhase;
	private static boolean benchLooked;

	/** The bench mod's current phase when it's loaded (stats only), else "game". */
	static String phase() {
		if (!benchLooked) {
			benchLooked = true;
			try {
				benchPhase = Class.forName("siliconshaders.bench.Bench").getDeclaredField("phase");
				benchPhase.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException absent) {
				benchPhase = null;
			}
		}
		if (benchPhase == null) return "game";
		try {
			return String.valueOf(benchPhase.get(null));
		} catch (ReflectiveOperationException e) {
			return "game";
		}
	}
}
