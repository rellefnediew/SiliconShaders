package siliconshaders.metal;

/** the removed flag metal.residentAnim: see MetalDevice. The flag and the marker its mixin sets while a sprite animation's frames are created. */
public final class ResidentAnim {
	public static final boolean ON = false;
	public static volatile boolean creating;

	private ResidentAnim() {
	}
}
