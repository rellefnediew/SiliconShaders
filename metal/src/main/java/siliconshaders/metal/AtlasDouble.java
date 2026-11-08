package siliconshaders.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import siliconshaders.metal.mixin.AtlasDoubleAtlasAccess;
import siliconshaders.metal.mixin.AtlasDoubleSpriteAccess;
import siliconshaders.metal.mixin.AtlasDoubleStateAccess;
import siliconshaders.metal.mixin.AtlasDoubleTextureAccess;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.system.MemoryUtil;

/**
 * the removed flag metal.atlasDouble: the blocks atlas double-buffered (2048^2, 5 mips: ~22 MB more); with the removed flag metal.atlasDoubleAll every atlas
 * with animated sprites.
 *
 * On a tick that animates, vanilla redraws the animated sprites into the atlas; the previous frame (still on the GPU) samples that atlas,
 * so the write waits for it to finish, and this frame's main pass waits behind the write: the frame loses its overlap with the previous one
 * (with the removed flag metal.lazyClears and the removed flag metal.lightmapDouble, the last of the three such waits). Here the atlas has two copies: a tick's
 * animation passes (vanilla's own, unchanged) draw into the copy nothing in flight samples, and only work recorded after the swap binds it.
 *
 * Keeping the spare current: each copy carries the sprites drawn into the other copy since it was last current (its stale set). On a tick
 * that draws, vanilla's own passes draw into the spare (its per-mip views stand in for the atlas's) this tick's sprites plus the spare's stale
 * ones (their dirty flag set for the upload, so vanilla's draw runs: a non-interpolated sprite that isn't dirty now still shows the frame it
 * was last drawn with, so it writes the same pixels it wrote into the other copy; interpolated ones draw every tick anyway); then the spare
 * becomes the atlas's texture and view. No copies, so nothing in the tick's work touches the copy the previous frame samples. Exact: every
 * change to the atlas after its upload is an animation draw, and the spare gets every draw the other copy got, with the same result. -Dsiliconshaders.metal.atlasDoubleVerify=N: every N-th swap both copies are read back and every texel
 * outside the rectangles drawn that tick compared (they must be equal).
 */
public final class AtlasDouble {
	private static final int VERIFY = Integer.getInteger("siliconshaders.metal.atlasDoubleVerify", 0);

	private static final class State {
		final GpuTexture[] tex = new GpuTexture[2];
		final GpuTextureView[] view = new GpuTextureView[2];
		final GpuTextureView[][] mips = new GpuTextureView[2][];
		boolean[][] stale;
		List<TextureAtlasSprite> sprites;
		int cur, maxMip, width, height, swaps;
		boolean[] drawn, forced;
	}

	private static final Map<TextureAtlas, State> STATES = new WeakHashMap<>();
	private static long verifyTexels, verifyBad;

	private AtlasDouble() {
	}


	private static boolean blocks(TextureAtlas atlas) {
		return atlas.location().getPath().endsWith("atlas/blocks.png");
	}


	/** Before the atlas's releaseTextures: vanilla's own copy back in place (it closes that), ours closed here. */
	public static void release(TextureAtlas atlas) {
		State s = STATES.remove(atlas);
		if (s == null) return;
		((AtlasDoubleTextureAccess) atlas).siliconshaders$adSetTexture(s.tex[0]);
		((AtlasDoubleTextureAccess) atlas).siliconshaders$adSetTextureView(s.view[0]);
		((AtlasDoubleAtlasAccess) atlas).siliconshaders$adSetMipViews(s.mips[0]);
		for (GpuTextureView v : s.mips[1]) v.close();
		s.view[1].close();
		s.tex[1].close();
	}



	/**
	 * Set by a driver that replaces vanilla's animation upload (the own renderer's one-pass animation): true for the uploads it takes over (this
	 * class's hooks on them stand aside; the driver draws into spare(), redrawing staleOfSpare() too, then calls committed()).
	 */
	public static volatile java.util.function.Predicate<TextureAtlas> takenOver = atlas -> false;

	/** The copy nothing in flight samples, or null if atlas isn't double-buffered. */
	public static GpuTexture spare(TextureAtlas atlas) {
		State s = STATES.get(atlas);
		return s == null ? null : s.tex[1 - s.cur];
	}

	/** The sprites (in animation-state order) the spare lacks, or null if atlas isn't double-buffered. */
	public static boolean[] staleOfSpare(TextureAtlas atlas) {
		State s = STATES.get(atlas);
		return s == null ? null : s.stale[1 - s.cur].clone();
	}

	/** The driver has drawn the tick's sprites (drawn: those that changed this tick) and the stale ones into the spare: swap. */
	public static void committed(TextureAtlas atlas, boolean[] drawn) {
		State s = STATES.get(atlas);
		if (s == null) return;
		int spare = 1 - s.cur;
		java.util.Arrays.fill(s.stale[spare], false);
		((AtlasDoubleTextureAccess) atlas).siliconshaders$adSetTexture(s.tex[spare]);
		((AtlasDoubleTextureAccess) atlas).siliconshaders$adSetTextureView(s.view[spare]);
		((AtlasDoubleAtlasAccess) atlas).siliconshaders$adSetMipViews(s.mips[spare]);
		boolean[] staleOld = s.stale[s.cur];
		for (int i = 0; i < staleOld.length && i < drawn.length; i++) staleOld[i] |= drawn[i];
		int old = s.cur;
		s.cur = spare;
		if (VERIFY > 0 && ++s.swaps % VERIFY == 0) verify(s, old, drawn);
	}

	/** Both copies read back; every texel outside this tick's drawn rectangles must be equal. */
	private static void verify(State s, int old, boolean[] drawn) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		int bpp = s.tex[0].getFormat().blockSize();
		for (int m = 0; m <= s.maxMip; m++) {
			int mip = m, w = Math.max(1, s.width >> m), h = Math.max(1, s.height >> m);
			ByteBuffer[] got = new ByteBuffer[2];
			Runnable compare = () -> {
				if (got[0] == null || got[1] == null) return;
				boolean[] in = new boolean[w * h];
				for (int i = 0; i < drawn.length; i++) {
					if (!drawn[i]) continue;
					TextureAtlasSprite sp = s.sprites.get(i);
					int p = ((AtlasDoubleSpriteAccess) sp).siliconshaders$adPadding(), rw = sp.contents().width() + 2 * p >> mip, rh = sp.contents().height() + 2 * p >> mip;
					for (int y = 0; y < rh; y++) for (int x = 0; x < rw; x++) in[((sp.getY() >> mip) + y) * w + (sp.getX() >> mip) + x] = true;
				}
				long bad = 0;
				for (int t = 0; t < w * h; t++) {
					if (in[t]) continue;
					verifyTexels++;
					for (int c = 0; c < bpp; c++) {
						if (got[0].get(t * bpp + c) != got[1].get(t * bpp + c)) {
							bad++;
							break;
						}
					}
				}
				verifyBad += bad;
				siliconshaders.metal.Log.info("siliconshaders-atlasdouble-verify mip " + mip + ": texels outside the drawn rectangles " + verifyTexels + ", different " + verifyBad);
				MemoryUtil.memFree(got[0]);
				MemoryUtil.memFree(got[1]);
			};
			for (int k = 0; k < 2; k++) {
				int which = k == 0 ? s.cur : old, slot = k;
				GpuBuffer b = device.createBuffer(() -> "siliconshaders atlas double verify", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
				encoder.copyTextureToBuffer(s.tex[which], b, 0, () -> {
					try (GpuBufferSlice.MappedView v = b.map(true, false)) {
						got[slot] = MemoryUtil.memAlloc(v.data().remaining()).put(v.data()).flip();
					}
					b.close();
					compare.run();
				}, mip);
			}
		}
	}
}
