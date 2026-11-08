package siliconshaders.metal.pack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import siliconshaders.metal.MetalBridge;

/** Render-thread-only native state. Extraction publishes only the primitive camera data the final pass needs. */
public final class ShaderRuntime {
    private static long handle,context;
    private static String key;
    private record Frame(boolean overworld,int water) { }
    private static volatile Frame frame=new Frame(false,0);
    private static final long START=System.nanoTime();
    private ShaderRuntime() { }

    public static void extract(boolean overworld,int water) { frame=new Frame(overworld,water); }
    public static boolean metalActive() { return "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()); }

    public static void render(RenderTarget target) {
        var manager=ShaderManager.get(); var state=manager.snapshot();
        if (!state.enabled() || state.program()==null) { close(); return; }
        if (!metalActive()) { close(); manager.phase(state.revision(),ShaderManager.Phase.NEEDS_METAL); return; }
        Frame inputs=frame;
        if (!inputs.overworld()) { manager.phase(state.revision(),ShaderManager.Phase.OVERWORLD_ONLY); return; }
        try {
            Object encoder=MetalBridge.encoder(((FrontendCommandEncoder)RenderSystem.getDevice().createCommandEncoder()).backend());
            if(encoder==null) return;
            long ctx=MetalBridge.ctx(encoder);
            if(handle==0 || context!=ctx || !state.program().key().equals(key)) {
                long next=PackNative.create(ctx,state.program().json(),MetalBridge.pixelFormat(target.getColorTexture().getFormat()));
                close(); handle=next; context=ctx; key=state.program().key();
            }
            var texture=target.getColorTexture();
            MetalBridge.flushClear(encoder,texture);
            float seconds=(float)(((System.nanoTime()-START)/1_000_000_000.0)%3600.0);
            PackNative.draw(MetalBridge.enc(encoder),handle,MetalBridge.textureHandle(texture),texture.getWidth(0),texture.getHeight(0),inputs.water(),seconds);
            manager.phase(state.revision(),ShaderManager.Phase.ACTIVE);
        } catch (RuntimeException | LinkageError e) { close(); manager.fail(state.revision(),e); }
    }

    public static void close() {
        if(handle!=0) { PackNative.release(handle); handle=0; key=null; context=0; }
    }
}
