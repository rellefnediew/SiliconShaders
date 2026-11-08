// Shadow-only temporal resolve. The old image is reprojected using the camera, never a cached solar pose.
extern int mc_render_begin(Enc *,int,id<MTLTexture> const *,const float *,id<MTLTexture>,int,float,int,int);
static void sunTemporalClear(Own *w) {
    [w->sunResolved release];w->sunResolved=nil;[w->sunRaw release];w->sunRaw=nil;[w->sunSceneCopy release];w->sunSceneCopy=nil;[w->sunDepthCopy release];w->sunDepthCopy=nil;
    for(int i=0;i<2;i++) { [w->sunHistory[i] release];w->sunHistory[i]=nil; }
    w->sunTemporalValid=0;w->sunHistoryIndex=0;w->sunTemporalSize=0;w->sunTemporalImageFormat=MTLPixelFormatInvalid;w->sunInputDepth=nil;
    sunGbufferClear(w);
}
static id<MTLTexture> sunImage(Own *w,int width,int height,MTLPixelFormat format,BOOL target) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead|(target?MTLTextureUsageRenderTarget:0);
    return [w->ctx->device newTextureWithDescriptor:td];
}
// Reading the current pixel from a color attachment uses Apple programmable blending.
// No color texture is simultaneously sampled and rendered. Depth is sampled only in later passes.
static BOOL sunFetchPrepare(Own *w,MTLPixelFormat format) {
    if(w->sunFetchFormat==format)return w->sunFetchReady;
    for(int i=0;i<3;i++){[w->sunFetchPipeline[i] release];w->sunFetchPipeline[i]=nil;}
    w->sunFetchFormat=format;w->sunFetchReady=0;
    if(![w->ctx->device supportsFamily:MTLGPUFamilyApple2])return NO;
    // Keep unvalidated color encodings on the established copied path.
    if(format!=MTLPixelFormatRGBA8Unorm && format!=MTLPixelFormatBGRA8Unorm &&
        format!=MTLPixelFormatRGBA8Unorm_sRGB && format!=MTLPixelFormatBGRA8Unorm_sRGB && format!=MTLPixelFormatRGBA16Float && format!=MTLPixelFormatRG11B10Float)return NO;
    NSError *issue=nil;
    const char *names[]={"sun_temporal_fetch_fs","sun_temporal_reference_fetch_fs","sun_deferred_apply_fetch_fs"};
    for(int i=0;i<3;i++) {
        id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=sunPlainFunction(w,names[i]);
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.vertexFunction=vs;pd.fragmentFunction=fs;
        pd.label=@"siliconshaders shadow resolve with color attachment input";
        pd.colorAttachments[0].pixelFormat=format;pd.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA16Float;
        w->sunFetchPipeline[i]=vs&&fs?surfaceRenderPipeline(w,pd,NO,&issue):nil;
        [vs release];[fs release];
        if(!w->sunFetchPipeline[i]) {
            for(int k=0;k<3;k++){[w->sunFetchPipeline[k] release];w->sunFetchPipeline[k]=nil;}
            fprintf(stderr,"[siliconshaders sun] Color attachment input unavailable; retaining copied resolve\n");return NO;
        }
    }
    w->sunFetchReady=1;return YES;
}

static int sunTemporalBegin(Own *w,Enc *enc,const uint8_t *packet,char *err,int cap) {
    w->sunTemporal=0;
    if(enc->colorCount!=1 || !enc->render || !enc->colors[0] || !enc->depth || enc->dead
        || enc->depth.pixelFormat!=MTLPixelFormatDepth32Float || enc->colors[0].sampleCount!=1 || enc->depth.sampleCount!=1
        || enc->colors[0].storageMode==MTLStorageModeMemoryless || enc->depth.storageMode==MTLStorageModeMemoryless) {
        w->sunTemporalValid=0;return 0;
    }
    int width=enc->width,height=enc->height;MTLPixelFormat format=enc->colors[0].pixelFormat;
    for(int mode=0;mode<2;mode++)if(!w->sunTemporalPipeline[mode] || w->sunTemporalFormat!=format) {
        NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=sunPlainFunction(w,mode?"sun_temporal_reference_fs":"sun_temporal_fs");
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
        pd.label=@"siliconshaders shadow temporal resolve";pd.vertexFunction=vs;pd.fragmentFunction=fs;
        pd.colorAttachments[0].pixelFormat=format;pd.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA16Float;
        id<MTLRenderPipelineState> pipeline=vs&&fs?surfaceRenderPipeline(w,pd,NO,&issue):nil;
        [vs release];[fs release];
        if(!pipeline) { ownErr(issue,"shadow temporal pipeline",err,cap);return -1; }
        [w->sunTemporalPipeline[mode] release];w->sunTemporalPipeline[mode]=pipeline;
    }
    w->sunTemporalFormat=format;
    BOOL fetchReady=sunFetchPrepare(w,format);
    BOOL memory=!w->sunMemoryReference && !w->sunForceMemoryFallback && fetchReady && enc->colors[0].textureType==MTLTextureType2D && enc->colors[0].hazardTrackingMode!=MTLHazardTrackingModeUntracked;
    BOOL directDepth=memory && (enc->depth.usage&MTLTextureUsageShaderRead) && enc->depth.textureType==MTLTextureType2D && enc->depth.hazardTrackingMode!=MTLHazardTrackingModeUntracked;
    if(!w->sunRaw || w->sunRaw.width!=width || w->sunRaw.height!=height || w->sunTemporalImageFormat!=format) {
        sunTemporalClear(w);
        w->sunRaw=sunImage(w,width,height,MTLPixelFormatRGBA16Float,YES);
        for(int i=0;i<2;i++)w->sunHistory[i]=sunImage(w,width,height,MTLPixelFormatRGBA16Float,YES);
        if(!w->sunRaw||!w->sunHistory[0]||!w->sunHistory[1]) {
            sunTemporalClear(w);snprintf(err,cap,"Cannot allocate shadow history");return -1;
        }
        w->sunTemporalImageFormat=format;
    }
    // Allocate copied inputs lazily. An optimized start needs neither when depth is readable.
    // Retain existing copies as a warm fallback when the user toggles modes.
    if(!memory && !w->sunSceneCopy)w->sunSceneCopy=sunImage(w,width,height,format,NO);
    if(!directDepth && !w->sunDepthCopy)w->sunDepthCopy=sunImage(w,width,height,MTLPixelFormatDepth32Float,NO);
    if((!memory&&!w->sunSceneCopy)||(!directDepth&&!w->sunDepthCopy)) {
        sunTemporalClear(w);snprintf(err,cap,"Cannot allocate copied shadow inputs");return -1;
    }
    w->sunMemoryActive=memory;w->sunMemoryDepthDirect=directDepth;w->sunMemoryCopyMask=0;
    w->sunInputDepth=directDepth?enc->depth:w->sunDepthCopy;
    memcpy(&w->sunCurrentView,packet+144,64);memcpy(w->sunCamera,packet+208,24);
    memcpy(&w->sunRevision,packet+232,8);memcpy(&w->sunDeltaTime,packet+240,4);
    if(!isfinite(w->sunDeltaTime)||w->sunDeltaTime<=0||w->sunDeltaTime>0.25)w->sunTemporalValid=0;
    float delta[4]={0};for(int i=0;i<3;i++)delta[i]=(float)(w->sunCamera[i]-w->sunPreviousCamera[i]);
    if(w->sunRevision!=w->sunPreviousRevision || simd_length((simd_float3){delta[0],delta[1],delta[2]})>8 || w->sunTemporalSize!=(int)*(const uint32_t *)(packet+136))
        w->sunTemporalValid=0;
    const float *direction=(const float *)(packet+96);
    float sunDot=direction[0]*w->sunPreviousDirection[0]+direction[1]*w->sunPreviousDirection[1]+direction[2]*w->sunPreviousDirection[2];
    if(sunDot<0.9998f)w->sunTemporalValid=0;
    float currentRange=w->sunCascaded?*(const float *)(w->sunBlendFrame+144):*(const float *)(packet+112);
    if(currentRange!=w->sunHistoryRange)w->sunTemporalValid=0;
    w->sunHistoryRange=currentRange;
    simd_float4x4 inverse=simd_inverse(w->sunCurrentView);
    for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(!isfinite(inverse.columns[c][r])) {
        snprintf(err,cap,"Invalid shadow camera projection");return -1;
    }
    memcpy(w->sunTemporalFrame,&inverse,64);memcpy(w->sunTemporalFrame+64,&w->sunPreviousView,64);
    memcpy(w->sunTemporalFrame+128,delta,16);
    float config[4]={width,height,1-expf(-fminf(0.25f,fmaxf(0.0001f,w->sunDeltaTime))/0.10f),w->sunTemporalValid?1:0};
    memcpy(w->sunTemporalFrame+144,config,16);
    w->sunSceneColor=enc->colors[0];w->sunSceneDepth=enc->depth;w->sunFog=nil;w->sunFogOffset=0;
    // w->sunGbuffer was requested by mco_sun_frame for the deferred path; allocation failure keeps two targets.
    if(w->sunGbuffer && !sunGbufferPrepare(w,width,height))w->sunGbuffer=0;
    id<MTLTexture> targets[]={w->sunSceneColor,w->sunRaw,w->sunAlbedo,w->sunLightData};float clears[]={0,0,0,0,0, 1,1,1,1,-1, 1,0,0,0,0, 1,0,0,0,0};
    mc_render_begin(enc,w->sunGbuffer?4:2,targets,clears,w->sunSceneDepth,0,0,width,height);
    enc->render.label=w->sunGbuffer?@"terrain with sun filter, mask and lighting G-buffer":@"terrain with sun filter and mask";
    w->sunTemporal=1;w->sunTemporalSequence=*(const uint32_t *)(packet+244);float index=(float)(w->sunTemporalSequence%3600);memcpy(w->sunBlendFrame+136,&index,4);
    return 1;
}
// Auroras and the nebula at half resolution per axis, after the terrain (its depth marks the sky) and before the lighting passes that
// sample them (sun.metal sun_aurora_fs, SUN_AURORA_LOW).
static int sunAuroraPass(Own *w,Enc *enc,id<MTLCommandBuffer> command,int width,int height,char *err,int cap) {
    int lw=(width+1)/2,lh=(height+1)/2;
    if(!w->sunAuroraLow||(int)w->sunAuroraLow.width!=lw||(int)w->sunAuroraLow.height!=lh) {
        [w->sunAuroraLow release];w->sunAuroraLow=sunImage(w,lw,lh,MTLPixelFormatRGBA16Float,YES);
        if(!w->sunAuroraLow){snprintf(err,cap,"Cannot allocate the aurora target");return -1;}
    }
    if(!w->sunAuroraPipeline) {
        NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=sunPlainFunction(w,"sun_aurora_fs");
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"siliconshaders auroras at half resolution";
        pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;
        w->sunAuroraPipeline=vs&&fs?surfaceRenderPipeline(w,pd,NO,&issue):nil;[vs release];[fs release];
        if(!w->sunAuroraPipeline){ownErr(issue,"aurora pass",err,cap);return -1;}
    }
    MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture=w->sunAuroraLow;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,rp);
    if(!r){snprintf(err,cap,"Cannot begin the aurora pass");return -1;}
    r.label=@"siliconshaders auroras at half resolution";[r setRenderPipelineState:w->sunAuroraPipeline];[r setCullMode:MTLCullModeNone];
    [r setViewport:(MTLViewport){0,0,lw,lh,0,1}];
    [r setFragmentBytes:w->sunTemporalFrame length:160 atIndex:0];[r setFragmentBytes:w->sunFrame length:144 atIndex:1];
    sunLightingBind(w,r);[r setFragmentTexture:nil atIndex:33];[r setFragmentTexture:sunSkyNoise(w) atIndex:34];[r setFragmentTexture:w->sunInputDepth atIndex:3];
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];
    return 1;
}
int mco_sun_finish(Own *w,Enc *enc,char *err,int cap) {
    // These FFM entry points have no caller-owned Cocoa autorelease pool.
    // Release temporary pass descriptors here, including their counter-buffer attachments.
    @autoreleasepool {
    if(!w->sunTemporal)return 0;
    int width=w->sunRaw.width,height=w->sunRaw.height,next=w->sunHistoryIndex^1;
    // Distinct color input/output avoids render-target sampling feedback.
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);
    // The rebuilt vanilla color replaces the terrain pass's color before copies and shadows read it.
    if(w->sunGbufferView==SUN_GBUFFER_REBUILT && sunGbufferPass(w,enc,SUN_GBUFFER_REBUILT,err,cap)<0)return -1;
    if(!w->sunMemoryActive || !w->sunMemoryDepthDirect) {
        id<MTLBlitCommandEncoder> copies=mc_profiled_blit(enc,command);
        copies.label=w->sunMemoryActive?@"sun temporal depth fallback copy":@"sun temporal color/depth copies";
        if(!w->sunMemoryActive) {
            [copies copyFromTexture:w->sunSceneColor sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(width,height,1) toTexture:w->sunSceneCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
            w->sunMemoryCopyMask|=1;
        }
        if(!w->sunMemoryDepthDirect) {
            [copies copyFromTexture:w->sunSceneDepth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(width,height,1) toTexture:w->sunDepthCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
            w->sunMemoryCopyMask|=2;
        }
        [copies endEncoding];[copies release];
    }
    BOOL direct=w->sunDeferred && !w->sunFilterReference && !w->sunForceSeparateResolve && *(const float *)(w->sunTemporalFrame+156)<.5f;
    // Shadows at half resolution: only the separate mask + optimized temporal resolve has a half variant.
    BOOL half=w->sunHalfMask && w->sunDeferred && !w->sunFilterReference;
    if(half&&w->sunHalfFormat!=w->sunSceneColor.pixelFormat){[w->sunHalfFetchPipeline release];[w->sunHalfTemporalPipeline release];w->sunHalfFetchPipeline=w->sunHalfTemporalPipeline=nil;w->sunHalfFormat=w->sunSceneColor.pixelFormat;}
    if(half&&!w->sunHalfMaskPipeline)w->sunHalfMaskPipeline=sunHalfPipeline(w,"sun_deferred_mask_fs",MTLPixelFormatRGBA16Float,MTLPixelFormatInvalid);
    if(half&&w->sunMemoryActive&&!w->sunHalfFetchPipeline)w->sunHalfFetchPipeline=sunHalfPipeline(w,"sun_temporal_fetch_fs",w->sunSceneColor.pixelFormat,MTLPixelFormatRGBA16Float);
    if(half&&!w->sunMemoryActive&&!w->sunHalfTemporalPipeline)w->sunHalfTemporalPipeline=sunHalfPipeline(w,"sun_temporal_fs",w->sunSceneColor.pixelFormat,MTLPixelFormatRGBA16Float);
    if(half&&(!w->sunHalfMaskPipeline||!(w->sunMemoryActive?w->sunHalfFetchPipeline:w->sunHalfTemporalPipeline)))half=NO;
    if(w->sunDeferred) {
        // The receiver grid only selects shadow casters; without direct sun no cascade is drawn.
        if(w->sunSurface.current.enabled && !(w->sunSurface.current.flags&OWN_SUN_NO_RECEIVER_GRID) &&
            *(const float *)(w->sunFrame+108)>0 && sunReceiverBuild(w,enc,err,cap)<0)return -1;
        for(int i=0;i<w->sunCascaded;i++) {
            const uint8_t *sample=w->sunDeferredPacket+(i?256+(i-1)*144:0);
            id<MTLTexture> map=i==0?w->sunMap:i==w->sunCascaded-1?w->sunSecondMap:w->sunMiddleMaps[i-1];
            if(sunEncodeCascade(w,enc,sample,map,w->sunDeferredArena,w->sunDeferredSections,w->sunDeferredRecords,
                w->sunDeferredAtlas,w->sunDeferredSampler,i,err,cap)<0)return -1;
        }
        if(sunGlassUpdate(w,enc,w->sunDeferredPacket,w->sunCascaded,err,cap)<0)return -1;
        // Colored transmission raises the fused shader's register/texture cost on M1 Pro.
        // Keep the measured separate path when any cascade uses glass transmission.
        if(direct)for(int i=0;i<w->sunCascaded;i++)if(w->sunGlassParams[16+i]>.5f){direct=NO;break;}
        if(direct)half=NO;
        if(!direct && sunDeferredResolve(w,enc,half,err,cap)<0)return -1;
    }
    if((w->sunFx&8)&&sunAuroraPass(w,enc,command,width,height,err,cap)<0)return -1;
    w->sunLastDirectResolve=direct;
    if(direct) {
        if(sunDeferredApply(w,enc,next,err,cap)<0)return -1;
    } else {
    MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture=w->sunSceneColor;rp.colorAttachments[1].texture=w->sunHistory[next];
    for(int i=0;i<2;i++) { rp.colorAttachments[i].loadAction=MTLLoadActionDontCare;rp.colorAttachments[i].storeAction=MTLStoreActionStore; }
    if(w->sunMemoryActive)rp.colorAttachments[0].loadAction=MTLLoadActionLoad;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,rp);
    if(!r) { snprintf(err,cap,"Cannot begin shadow temporal resolve");return -1; }
    r.label=w->sunMemoryActive?@"siliconshaders shadow temporal: color attachment input":w->sunFilterReference?@"siliconshaders shadow temporal: reference filter":@"siliconshaders shadow temporal: optimized filter";
    if(half)r.label=@"siliconshaders shadow temporal: half-resolution visibility";
    [r setRenderPipelineState:half?(w->sunMemoryActive?w->sunHalfFetchPipeline:w->sunHalfTemporalPipeline):w->sunMemoryActive?w->sunFetchPipeline[w->sunFilterReference]:w->sunTemporalPipeline[w->sunFilterReference]];[r setCullMode:MTLCullModeNone];
    if(half)[r setFragmentTexture:w->sunRaw atIndex:8]; // each pixel's own surface kind for the upsampling
    [r setViewport:(MTLViewport){0,0,width,height,0,1}];
    [r setFragmentBytes:w->sunTemporalFrame length:160 atIndex:0];
    [r setFragmentBytes:w->sunFrame length:144 atIndex:1];
    if(w->sunFog)[r setFragmentBuffer:w->sunFog offset:w->sunFogOffset atIndex:24];
    else { uint8_t fog[64]={0};[r setFragmentBytes:fog length:64 atIndex:24]; }
    [r setFragmentTexture:w->sunMemoryActive?nil:w->sunSceneCopy atIndex:0];[r setFragmentTexture:w->sunDeferred?w->sunResolved:w->sunRaw atIndex:1];
    [r setFragmentTexture:w->sunHistory[w->sunHistoryIndex] atIndex:2];[r setFragmentTexture:w->sunInputDepth atIndex:3];
    sunLightingBind(w,r);
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];
    }
    if(w->sunGbufferView && w->sunGbufferView<SUN_GBUFFER_REBUILT && sunGbufferPass(w,enc,w->sunGbufferView,err,cap)<0)return -1;
    w->sunHistoryIndex=next;w->sunTemporalValid=1;w->sunPreviousView=w->sunCurrentView;
    memcpy(w->sunPreviousDirection,w->sunFrame+96,12);memcpy(w->sunPreviousCamera,w->sunCamera,24);w->sunPreviousRevision=w->sunRevision;w->sunTemporalSize=w->sunSize;
    w->sunTemporal=0;w->sunDeferred=0;w->sunGbuffer=0;w->sunLightmap=nil;w->sunLightmapSampler=nil;id<MTLTexture> original[]={w->sunSceneColor};float load[5]={0};
    mc_render_begin(enc,1,original,load,w->sunSceneDepth,0,0,width,height);
    w->sunSceneColor=w->sunSceneDepth=w->sunInputDepth=nil;return 1;
    }
}
