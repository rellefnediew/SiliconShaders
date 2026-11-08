package siliconshaders.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dsiliconshaders.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("siliconshaders.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	/** -Dsiliconshaders.metal.paceMarginMs: how long before a refresh the paced present must be done (the compositor's latch); default 2. */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("siliconshaders.metal.paceMarginMs", "2")) / 1000;
	/** -Dsiliconshaders.metal.drawables=2|3: the layer's maximumDrawableCount (default 3). */
	private static final int DRAWABLES = Integer.getInteger("siliconshaders.metal.drawables", 0);
	/**
	 * -Dsiliconshaders.metal.paceAdapt=true: the pacer's margin learns the compositor's latch from scanout times (mc_pace in mcmetal.m): a
	 * paced frame shown more than half a refresh after the refresh it was aimed at adds lead, a frame on time takes a little away.
	 */
	static final boolean PACE_ADAPT = Boolean.getBoolean("siliconshaders.metal.paceAdapt");
	private boolean paced;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		this.paced = !vsync && PACE;
		if (PACE_ADAPT) Native.paceAdapt(this.paced ? 1 : 0);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync ? 1 : 0) | DRAWABLES << 8);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		// siliconshaders.rec hook: the opt-in recorder (-Dsiliconshaders.rec, siliconshaders.metal.rec) takes the finished frame, GUI included. Rec.ON is a constant false without it.
		if (siliconshaders.metal.rec.Rec.ON) siliconshaders.metal.rec.Rec.frame(this.encoder, textureView);
		long t0 = WaitStats.ON ? System.nanoTime() : 0;
		if (this.paced && !Native.pace(PACE_MARGIN_S)) {
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - t0, 0);
			return;
		}
		long t1 = WaitStats.ON ? System.nanoTime() : 0;
		if (WaitStats.ON && this.paced) WaitStats.wait(WaitStats.PACE, t1 - t0, 0);
		
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable;
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
		}
		if (WaitStats.ON) WaitStats.wait(WaitStats.DRAWABLE, System.nanoTime() - t1, 0);
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
