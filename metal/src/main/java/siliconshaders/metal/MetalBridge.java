package siliconshaders.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import java.lang.foreign.SymbolLookup;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The shaderpack runtime's (siliconshaders.metal.pack) window into this package: native handles of the backend's objects and
 * the loaded native library. Everything the pack package needs from the backend goes through here, so the backend's
 * own classes stay package-private and its hot paths untouched.
 */
public final class MetalBridge {
    /** Called before frontend format validation, only for siliconshaders's actual HDR world attachment. */
    public static com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline hdrPipeline(com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline source) {
        if(source instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend && frontend.backendRenderPipeline() instanceof MetalPipeline metal)return metal.hdrVariant(frontend);
        return source;
    }

	/** Blocks until the GPU has finished every submitted frame (MetalEncoder.waitIdle): own's level-change release (OwnTerrain.disposeAll). */
	public static void waitIdle(Object encoder) {
		((MetalEncoder) encoder).waitIdle();
	}

	private MetalBridge() {
	}

	/** libsiliconshaders as loaded by the backend; mcpack.m's functions live in the same image. */
	public static SymbolLookup library() {
		return Native.lookup();
	}



	public static void commitAndWait(long enc) {
		long cmd = Native.encCommit(enc);
		Native.cmdWait(cmd);
		Native.release(cmd);
	}


	public static long newBuffer(long ctx, long size) {
		return Native.bufferNew(ctx, size);
	}

	public static long bufferContents(long buffer) {
		return Native.bufferContents(buffer);
	}

	public static void readTexture(long enc, long texture, int width, int height, int bytesPerPixel, long buffer) {
		Native.blitTextureToBuffer(enc, texture, 0, 0, 0, width, height, buffer, 0, width * bytesPerPixel);
	}


	public static int pixelFormat(GpuFormat format) {
		return MetalConst.pixelFormat(format);
	}




	/** MTLCompareFunction for op. */
	public static int compare(CompareOp op) {
		return MetalConst.compare(op);
	}



	public static long textureHandle(GpuTexture texture) {
		return ((MetalTexture) texture).handle;
	}

	/** Whether the texture was created with ShaderWrite usage (a compute kernel may write it). */
	public static boolean shaderWritable(GpuTexture texture) {
		return texture instanceof MetalTexture t && t.shaderWrite;
	}

	public static long viewHandle(GpuTextureView view) {
		return ((MetalTexture.View) view).handle;
	}

	public static long samplerHandle(GpuSampler sampler) {
		return ((MetalSampler) sampler).handle();
	}

	/**
	 * mc_sampler_new's arguments for sampler, as MetalSampler.create computed them: {addressU, addressV, min, mag, mip, anisotropy,
	 * maxLod} (the own renderer's frame capture, -Dsiliconshaders.own.capture, records them for its replay).
	 */
	public static float[] samplerParams(GpuSampler sampler) {
		MetalSampler s = (MetalSampler) sampler;
		double lod = s.getMaxLod().orElse(1000.0);
		return new float[] {MetalConst.addressMode(s.getAddressModeU()), MetalConst.addressMode(s.getAddressModeV()), MetalConst.filter(s.getMinFilter()),
			MetalConst.filter(s.getMagFilter()), lod > 0.25 ? 2 : 0, Math.max(1, s.getMaxAnisotropy()), (float) Math.max(0.25, lod)};
	}


	/** The backend's encoder behind a frontend command encoder, or null when the game isn't on the Metal backend. */
	public static @Nullable Object encoder(CommandEncoderBackend backend) {
		return backend instanceof MetalEncoder e ? e : null;
	}

	/**
	 * Native handle of buffer, marked as used by the frame being recorded (so the backend won't rewrite it in place while
	 * the GPU may still read it): for drawing with the game's own uniform buffers (siliconshaders.metal.own).
	 */
	public static long useBuffer(Object encoder, GpuBuffer buffer) {
		return ((MetalEncoder) encoder).use(buffer).handle;
	}

	/** CPU address of buffer's current memory (0 for GPU-only storage), for reading what the CPU last wrote into it. */
	public static long bufferAddress(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).address;
	}

	/** Native Enc* of encoder (the object encoder() returned). */
	public static long enc(Object encoder) {
		return ((MetalEncoder) encoder).enc;
	}

	/** Whether encoder is recording the submit -Dsiliconshaders.metal.trace names (whose operations and encoder GPU times get printed). */
	public static boolean tracing(Object encoder) {
		return ((MetalEncoder) encoder).tracing();
	}

	public static long ctx(Object encoder) {
		return ((MetalEncoder) encoder).ctx;
	}

	/**
	 * Opens (or continues) a render encoder on colors (0 = unused slot) and depth, like the backend's own passes: clears[i]
	 * null loads, else clears to {r, g, b, a}. Returns mc_render_begin's result (0 new encoder, 1 or 2 continued).
	 */
	/** A clears entry meaning: don't load this attachment, the pass writes all of it. */
	public static final float[] DONT_CARE = new float[0];





	/** The texture's contents are dead after the open render pass: it isn't stored (store action don't care). */
	public static void discard(long enc, long texture) {
		Native.discard(enc, texture);
	}

	/** A pending (deferred) clear of texture written to memory now, for reading it outside a render pass. */
	public static void flushClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).flushClear(texture);
	}



	public static void vertexBuffer(long enc, int index, long buffer, long offset) {
		Native.vertexBuffer(enc, index, buffer, offset);
	}


	public static @Nullable String pipelineName(Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.name : null;
	}

	/**
	 * Far terrain (siliconshaders.metal.lod, -Dsiliconshaders.lod) drew into the open render pass with its own pipeline: put the pass's current
	 * pipeline state back. False when no pass is open (nothing was drawn into one).
	 */
	public static boolean reapplyPipeline(Object encoder) {
		return ((MetalEncoder) encoder).reapplyPipeline();
	}

	/**
	 * The open render pass was split (its render encoder ended and reopened on the same attachments, mc_render_suspend /
	 * mc_render_resume): bind all of the pass's state again. False when no pass is open.
	 */
	public static boolean restorePass(Object encoder) {
		return ((MetalEncoder) encoder).restorePass();
	}

	/** Whether a render pass is open on encoder (far terrain draws only into one). */
	public static boolean inRenderPass(Object encoder) {
		return ((MetalEncoder) encoder).inRenderPass();
	}

	public static void release(long handle) {
		Native.release(handle);
	}
}
