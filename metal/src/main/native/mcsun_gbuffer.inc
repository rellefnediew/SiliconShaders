// Lighting G-buffer of the deferred terrain pass. Beside the scene color and sunRaw (normal, fog),
// the terrain writes sunAlbedo (texture x vertex tint, before the lightmap) and sunLightData
// (block light, sky light, material class, chunk fade). Views: 1 albedo, 2 light, 3 normals,
// 4 material, 5 vanilla lighting rebuilt from the G-buffer (the base later lighting models replace).
enum { SUN_GBUFFER_OFF, SUN_GBUFFER_ALBEDO, SUN_GBUFFER_LIGHT, SUN_GBUFFER_NORMALS, SUN_GBUFFER_MATERIAL, SUN_GBUFFER_REBUILT };

void mco_sun_gbuffer(Own *w,int enabled,int view) {
    w->sunGbufferEnabled=enabled!=0;w->sunGbufferView=enabled?MAX(0,MIN(SUN_GBUFFER_REBUILT,view)):0;
}
int mco_sun_debug_gbuffer(Own *w) { return w->sunGbuffer; }
id<MTLTexture> mco_sun_debug_normals(Own *w) { return w->sunRaw; }
id<MTLTexture> mco_sun_debug_albedo(Own *w) { return w->sunAlbedo; }
id<MTLTexture> mco_sun_debug_light_data(Own *w) { return w->sunLightData; }

// Releases the targets only; this frame's request (w->sunGbuffer) survives sunTemporalClear's reallocation.
static void sunGbufferClear(Own *w) {
    [w->sunAuroraNoise release];w->sunAuroraNoise=nil;[w->sunSkyNoise release];w->sunSkyNoise=nil;
    [w->sunAlbedo release];w->sunAlbedo=nil;[w->sunLightData release];w->sunLightData=nil;
}
static BOOL sunGbufferPrepare(Own *w,int width,int height) {
    if(w->sunAlbedo && w->sunAlbedo.width==(NSUInteger)width && w->sunAlbedo.height==(NSUInteger)height && w->sunLightData)return YES;
    sunGbufferClear(w);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    w->sunAlbedo=[w->ctx->device newTextureWithDescriptor:td];w->sunLightData=[w->ctx->device newTextureWithDescriptor:td];
    if(!w->sunAlbedo||!w->sunLightData){sunGbufferClear(w);fprintf(stderr,"[siliconshaders sun] G-buffer allocation failed; terrain keeps two targets\n");return NO;}
    return YES;
}
// The native lighting model (sun.metal sunTerrainColor) replaces vanilla's lightmap whenever the
// G-buffer is active, except in the "vanilla comparison" view, which rebuilds the vanilla color.
static void sunLightingBind(Own *w,id<MTLRenderCommandEncoder> r) {
    MTLPixelFormat format=w->sunSceneColor.pixelFormat;
    const float *look=w->sunLookSet?w->sunLook:(const float[]){1,1,1};
    OwnSunLighting lighting;memset(&lighting,0,sizeof lighting);
    lighting.enabled=w->sunGbuffer&&w->sunAlbedo&&w->sunLightData&&w->sunGbufferView!=SUN_GBUFFER_REBUILT;
    lighting.srgb=format==MTLPixelFormatRGBA8Unorm_sRGB||format==MTLPixelFormatBGRA8Unorm_sRGB;
    lighting.exposure=look[0];lighting.saturation=look[1];lighting.contrast=look[2];
    sunLightingFill(w,&lighting);
    blockLightBind(w,r);
    [r setFragmentTexture:sunAuroraNoise(w) atIndex:32];[r setFragmentTexture:w->sunAuroraLow atIndex:33];
    [r setFragmentBytes:&lighting length:sizeof lighting atIndex:4];w->sunLastLighting=lighting.enabled;
    [r setFragmentTexture:w->sunAlbedo?:w->sunRaw atIndex:5];[r setFragmentTexture:w->sunLightData?:w->sunRaw atIndex:6];
    [r setFragmentTexture:w->sunRaw atIndex:7];
}
int mco_sun_debug_lighting(Own *w) { return w->sunLastLighting; }
// The viewer's look: exposure 0.25-4, saturation 0-2, contrast 0.5-2 (neutral 1, 1, 1).
void mco_sun_look(Own *w,float exposure,float saturation,float contrast) {
    if(!isfinite(exposure)||!isfinite(saturation)||!isfinite(contrast))return;
    w->sunLook[0]=fminf(fmaxf(exposure,0.25f),4);w->sunLook[1]=fminf(fmaxf(saturation,0),2);w->sunLook[2]=fminf(fmaxf(contrast,0.5f),2);w->sunLookSet=1;
}

// One full-screen pass over the scene color: a debug view after the shadow resolve, or the rebuilt
// vanilla color before it. Pixels without terrain (sunRaw w < 0) keep their color.
static int sunGbufferPass(Own *w,Enc *enc,int view,char *err,int cap) {
    if(!w->sunGbuffer||!view)return 0;
    if(view==SUN_GBUFFER_REBUILT&&!w->sunLightmap)return 0;
    MTLPixelFormat format=w->sunSceneColor.pixelFormat;
    if(!w->sunGbufferPass||w->sunGbufferPassFormat!=format) {
        NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=[w->library newFunctionWithName:@"sun_gbuffer_view_fs"];
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"siliconshaders lighting G-buffer view";
        pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=format;
        id<MTLRenderPipelineState> pipeline=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue]:nil;
        [vs release];[fs release];if(!pipeline){ownErr(issue,"G-buffer view",err,cap);return -1;}
        [w->sunGbufferPass release];w->sunGbufferPass=pipeline;w->sunGbufferPassFormat=format;
    }
    MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture=w->sunSceneColor;rp.colorAttachments[0].loadAction=MTLLoadActionLoad;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,mc_frame_cmd(enc),rp);
    if(!r){snprintf(err,cap,"Cannot begin G-buffer view");return -1;}
    r.label=view==SUN_GBUFFER_REBUILT?@"sun G-buffer: rebuilt vanilla lighting":@"sun G-buffer: debug view";
    [r setRenderPipelineState:w->sunGbufferPass];[r setCullMode:MTLCullModeNone];
    [r setViewport:(MTLViewport){0,0,w->sunRaw.width,w->sunRaw.height,0,1}];
    uint32_t mode=(uint32_t)view;[r setFragmentBytes:w->sunFrame length:144 atIndex:1];[r setFragmentBytes:&mode length:4 atIndex:2];
    if(w->sunFog)[r setFragmentBuffer:w->sunFog offset:w->sunFogOffset atIndex:24];
    else{uint8_t fog[64]={0};[r setFragmentBytes:fog length:64 atIndex:24];}
    [r setFragmentTexture:w->sunRaw atIndex:0];[r setFragmentTexture:w->sunAlbedo atIndex:1];[r setFragmentTexture:w->sunLightData atIndex:2];
    if(!w->sunPlainSampler){MTLSamplerDescriptor *sd=[[[MTLSamplerDescriptor alloc] init] autorelease];w->sunPlainSampler=[w->ctx->device newSamplerStateWithDescriptor:sd];}
    [r setFragmentTexture:w->sunLightmap?:w->sunAlbedo atIndex:3];[r setFragmentSamplerState:w->sunLightmapSampler?:w->sunPlainSampler atIndex:0];
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];
    return 0;
}
