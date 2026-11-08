package siliconshaders.metal;

/** The frame limiter's wait, on a timer macOS can't defer; see mc_sleep_precise and FramerateLimiterMixin. */
public final class FrameWait {
	/** -Dsiliconshaders.preciseLimiter=false keeps vanilla's park-then-spin wait. */
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("siliconshaders.preciseLimiter", "true"));

	private FrameWait() {
	}

	public static void sleep(long nanos) {
		Native.sleepPrecise(nanos);
	}
}
