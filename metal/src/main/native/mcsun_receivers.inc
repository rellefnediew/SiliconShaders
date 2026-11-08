// Current-frame light-space receiver grids. No GPU-to-CPU dependency in the render path.
typedef struct {
    simd_float4x4 inverse,view,clips[4];
    simd_float4 guards;
    simd_uint4 config;
} OwnSunReceiverFrame;
_Static_assert(sizeof(OwnSunReceiverFrame)==416,"Receiver grid ABI");
static int sunReceiverPrepare(Own *w) {
    @autoreleasepool {
        NSError *issue=nil;
        const char *names[]={"sun_receivers_reset","sun_receivers_mark","sun_receivers_transparent","sun_cull_actors","sun_receivers_transparent_bounds"};
        id<MTLComputePipelineState> *targets[]={&w->sunReceiverReset,&w->sunReceiverMark,&w->sunReceiverTransparent,&w->sunReceiverActors,&w->sunReceiverTransparentBounds};
        for(int i=0;i<5;i++)if(!*targets[i]) {
            id<MTLFunction> f=[w->library newFunctionWithName:[NSString stringWithUTF8String:names[i]]];
            *targets[i]=f?[w->ctx->device newComputePipelineStateWithFunction:f error:&issue]:nil;[f release];
            if(!*targets[i])return 0;
        }
        if(!w->sunReceiverOptimizedTried) {
            w->sunReceiverOptimizedTried=1;
            id<MTLFunction> f=[w->library newFunctionWithName:@"sun_receivers_mark_optimized"];
            w->sunReceiverMarkOptimized=f?[w->ctx->device newComputePipelineStateWithFunction:f error:&issue]:nil;[f release];
            if(!w->sunReceiverMarkOptimized)fprintf(stderr,"[siliconshaders sun] Optimized receiver scan unavailable; retaining reference scan\n");
        }
        for(int mode=0;mode<2;mode++)if(!w->sunDeferredMaskPipeline[mode]) {
            id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=sunPlainFunction(w,mode?"sun_deferred_mask_reference_fs":"sun_deferred_mask_fs");
            MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
            pd.label=@"siliconshaders current-frame shadow visibility";pd.vertexFunction=vs;pd.fragmentFunction=fs;
            pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;
            w->sunDeferredMaskPipeline[mode]=vs&&fs?surfaceRenderPipeline(w,pd,NO,&issue):nil;
            [vs release];[fs release];if(!w->sunDeferredMaskPipeline[mode])return 0;
        }
        if(!w->sunReceiverBins)w->sunReceiverBins=[w->ctx->device newBufferWithLength:4*4097*4 options:MTLResourceStorageModePrivate];
        if(!w->sunReceiverStats)w->sunReceiverStats=[w->ctx->device newBufferWithLength:4*32 options:MTLResourceStorageModePrivate];
        if(w->sunCasterCount) {
            id<MTLBuffer> data=[w->ctx->device newBufferWithBytes:w->sunCasters length:w->sunCasterCount*sizeof(OwnSunCaster) options:MTLResourceStorageModeShared];
            if(!data)return 0;[w->sunActorData release];w->sunActorData=data;
            NSUInteger bytes=(NSUInteger)w->sunCasterCount*32*4;
            if(!w->sunActorArgs || w->sunActorArgs.length<bytes) {
                id<MTLBuffer> args=[w->ctx->device newBufferWithLength:bytes options:MTLResourceStorageModePrivate];
                if(!args)return 0;[w->sunActorArgs release];w->sunActorArgs=args;
            }
        }
        return w->sunReceiverBins && w->sunReceiverStats;
    }
}
static int sunReceiverBuild(Own *w,Enc *enc,char *err,int cap) {
    w->sunReceiverReady=0;
    OwnSunReceiverFrame f={};
    f.view=w->sunCurrentView;f.inverse=simd_inverse(f.view);
    for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(!isfinite(f.inverse.columns[c][r])) {
        snprintf(err,cap,"Invalid current receiver projection");return -1;
    }
    for(int i=0;i<w->sunCascaded;i++) {
        const uint8_t *p=w->sunDeferredPacket+(i?256+(i-1)*144:0);
        memcpy(f.clips+i,p,64);
        // Six map texels cover PCF (near radius 4), bilinear comparisons and normal bias.
        float range=*(const float *)(p+112),resolution=*(const uint32_t *)(p+136);
        f.guards[i]=12.0f/MAX(1,resolution)+.05f/range;
    }
    f.config=(simd_uint4){(uint32_t)w->sunRaw.width,(uint32_t)w->sunRaw.height,(uint32_t)w->sunCascaded,
        w->sunTransparentReceivers?w->sunTransparentReceiverCount:w->sunCandidates?(uint32_t)w->sunCandidateCount:((const uint32_t *)w->sunFrame)[32]};
    id<MTLComputeCommandEncoder> c=mc_profiled_compute(enc,mc_frame_cmd(enc),MTLDispatchTypeSerial);
    BOOL optimized=!w->sunReceiverReference && w->sunReceiverMarkOptimized;
    w->sunLastReceiverOptimized=optimized;
    c.label=optimized?@"sun receiver grid: opaque current-frame depth [optimized]":@"sun receiver grid: opaque current-frame depth";
    [c setComputePipelineState:w->sunReceiverReset];
    [c setBuffer:w->sunReceiverBins offset:0 atIndex:0];[c setBuffer:w->sunReceiverStats offset:0 atIndex:1];
    [c dispatchThreads:MTLSizeMake(4*4097,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
    [c memoryBarrierWithScope:MTLBarrierScopeBuffers];
    [c setComputePipelineState:optimized?w->sunReceiverMarkOptimized:w->sunReceiverMark];[c setBytes:&f length:sizeof f atIndex:0];
    [c setBuffer:w->sunReceiverBins offset:0 atIndex:1];
    [c setTexture:w->sunInputDepth atIndex:0];[c setTexture:w->sunRaw atIndex:1];
    [c dispatchThreads:MTLSizeMake(f.config.x,f.config.y,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];
    [c endEncoding];[c release];
    if(f.config.w) {
        c=mc_profiled_compute(enc,mc_frame_cmd(enc),MTLDispatchTypeSerial);
        c.label=@"sun receiver grid: transparent mesh bounds";
        [c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:w->sunReceiverBins offset:0 atIndex:1];
        if(w->sunTransparentReceivers) {
            [c setComputePipelineState:w->sunReceiverTransparentBounds];
            [c setBuffer:w->sunTransparentReceivers offset:0 atIndex:2];
        } else {
            // Compatibility for native fixtures/older callers; game frames always supply real mesh bounds.
            [c setComputePipelineState:w->sunReceiverTransparent];
            uint8_t sun[144];memcpy(sun,w->sunFrame,144);
            ((uint32_t *)sun)[33]=(uint32_t)(w->sunDeferredRecords.length/32);
            if(w->sunCandidates)((uint32_t *)sun)[35]|=4u;
            [c setBytes:sun length:sizeof sun atIndex:2];[c setBuffer:w->sunDeferredSections offset:0 atIndex:3];
            [c setBuffer:w->sunDeferredRecords offset:0 atIndex:4];[c setBuffer:w->sunCandidates?:w->sunReceiverBins offset:0 atIndex:5];
        }
        [c dispatchThreads:MTLSizeMake(f.config.w,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
        [c endEncoding];[c release];
    }
    w->sunReceiverReady=1;return 0;
}
// The SUN_HALF variant of a screen pass: one shader, specialized by function constant 31.
static id<MTLRenderPipelineState> sunHalfPipeline(Own *w,const char *function,MTLPixelFormat color0,MTLPixelFormat color1) {
    NSError *issue=nil;
    id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=sunLitFunction(w,function,YES);
    MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"siliconshaders shadow pass at half resolution";
    pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=color0;if(color1)pd.colorAttachments[1].pixelFormat=color1;
    id<MTLRenderPipelineState> next=vs&&fs?surfaceRenderPipeline(w,pd,YES,&issue):nil;[vs release];[fs release];
    if(!next)fprintf(stderr,"[siliconshaders sun] Half-resolution shadow pass %s unavailable: %s\n",function,issue.localizedDescription.UTF8String?:"missing");
    return next;
}
static int sunDeferredResolve(Own *w,Enc *enc,BOOL half,char *err,int cap) {
    int width=(int)w->sunRaw.width,height=(int)w->sunRaw.height,fullWidth=width,fullHeight=height;
    if(half){width=(width+1)/2;height=(height+1)/2;}
    if(!w->sunResolved || w->sunResolved.width!=width || w->sunResolved.height!=height) {
        [w->sunResolved release];w->sunResolved=sunImage(w,width,height,MTLPixelFormatRGBA16Float,YES);
        if(!w->sunResolved){snprintf(err,cap,"Cannot allocate current shadow mask");return -1;}
    }
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);
    MTLRenderPassDescriptor *rp=[[[MTLRenderPassDescriptor alloc] init] autorelease];
    rp.colorAttachments[0].texture=w->sunResolved;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;
    rp.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,rp);
    if(!r){snprintf(err,cap,"Cannot begin current shadow resolve");return -1;}
    (void)fullWidth;(void)fullHeight;
    r.label=half?@"sun visibility: current-frame receivers (half resolution)":@"sun visibility: current-frame receivers";
    [r setRenderPipelineState:half?w->sunHalfMaskPipeline:w->sunDeferredMaskPipeline[w->sunFilterReference]];
    [r setCullMode:MTLCullModeNone];[r setViewport:(MTLViewport){0,0,width,height,0,1}];
    [r setFragmentBytes:w->sunTemporalFrame length:160 atIndex:0];[r setFragmentBytes:w->sunFrame length:144 atIndex:1];
    [r setFragmentBytes:w->sunBlendFrame length:320 atIndex:2];[r setFragmentBytes:w->sunGlassParams length:sizeof w->sunGlassParams atIndex:3];
    [r setFragmentTexture:w->sunRaw atIndex:0];[r setFragmentTexture:w->sunInputDepth atIndex:1];
    [r setFragmentSamplerState:w->sunSampler atIndex:13];
    for(int i=0;i<4;i++) {
        id<MTLTexture> map=i==0?w->sunMap:i==w->sunCascaded-1?w->sunSecondMap:i<3?w->sunMiddleMaps[i-1]:nil;
        [r setFragmentTexture:map?:w->sunMap atIndex:20+i];
        [r setFragmentTexture:w->sunGlassDepth[i]?:w->sunMap atIndex:24+i];
        [r setFragmentTexture:w->sunGlassColor[i]?:w->sunGlassWhite atIndex:28+i];
    }
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];
    // Sparse, asynchronous diagnostics. Never wait or read a buffer still in flight.
    // mco_sun_frame decided before selection whether this frame collects the counters.
    if(w->sunReceiverReady && w->sunStatsFrame && !w->sunForceStats) {
        w->sunReceiverLogTime=CACurrentMediaTime();
        id<MTLBuffer> read=[w->ctx->device newBufferWithLength:144 options:MTLResourceStorageModeShared];
        if(read) {
            id<MTLBlitCommandEncoder> blit=mc_profiled_blit(enc,command);blit.label=@"sun receiver telemetry (async)";
            [blit copyFromBuffer:w->sunReceiverStats sourceOffset:0 toBuffer:read destinationOffset:0 size:128];
            for(int i=0;i<4;i++)[blit copyFromBuffer:w->sunReceiverBins sourceOffset:i*4097*4 toBuffer:read destinationOffset:128+i*4 size:4];
            [blit endEncoding];[blit release];
            int candidates=w->sunCandidates?w->sunCandidateCount:-1,levels=w->sunCascaded;uint32_t slots=((uint32_t *)w->sunFrame)[32];
            [command addCompletedHandler:^(id<MTLCommandBuffer> done) {
                if(done.status!=MTLCommandBufferStatusCompleted)return;
                const uint32_t *v=read.contents;
                fprintf(stderr,"[siliconshaders sun] current-frame receiver culling");
                if(candidates>=0)fprintf(stderr,"; CPU %d/%u slots",candidates,slots);
                else fprintf(stderr,"; CPU candidate list bypassed (%u slots)",slots);
                for(int i=0;i<levels;i++)fprintf(stderr,"; L%d %s: sections %u/%u rejected, records %u/%u rejected, %u quads",i,
                    v[32+i]?"fallback":"active",v[i*8+1],v[i*8],v[i*8+3],v[i*8+2],v[i*8+4]);
                for(int i=0;i<levels;i++)if(v[i*8+5])fprintf(stderr,"; L%d actors %u/%u rejected",i,v[i*8+6],v[i*8+5]);
                fprintf(stderr,"\n");
            }];
            [read release];
        }
    }
    return 0;
}

// No history is usable: evaluate and apply exactly one current shadow sample in one pass.
static int sunDeferredApply(Own *w,Enc *enc,int next,char *err,int cap) {
    MTLPixelFormat format=w->sunSceneColor.pixelFormat;
    if(!w->sunMemoryActive && (!w->sunDirectPipeline || w->sunDirectFormat!=format)) {
        NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=sunPlainFunction(w,"sun_deferred_apply_fs");
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.vertexFunction=vs;pd.fragmentFunction=fs;
        pd.colorAttachments[0].pixelFormat=format;pd.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA16Float;
        id<MTLRenderPipelineState> pipeline=vs&&fs?surfaceRenderPipeline(w,pd,NO,&issue):nil;
        [vs release];[fs release];if(!pipeline){ownErr(issue,"direct shadow apply",err,cap);return -1;}
        [w->sunDirectPipeline release];w->sunDirectPipeline=pipeline;w->sunDirectFormat=format;
    }
    MTLRenderPassDescriptor *rp=[[[MTLRenderPassDescriptor alloc] init] autorelease];
    rp.colorAttachments[0].texture=w->sunSceneColor;rp.colorAttachments[1].texture=w->sunHistory[next];
    for(int i=0;i<2;i++){rp.colorAttachments[i].loadAction=MTLLoadActionDontCare;rp.colorAttachments[i].storeAction=MTLStoreActionStore;}
    if(w->sunMemoryActive)rp.colorAttachments[0].loadAction=MTLLoadActionLoad;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,mc_frame_cmd(enc),rp);
    if(!r){snprintf(err,cap,"Cannot begin direct shadow apply");return -1;}
    r.label=w->sunMemoryActive?@"sun visibility and apply: color attachment input":@"sun visibility and apply: no usable history";[r setRenderPipelineState:w->sunMemoryActive?w->sunFetchPipeline[2]:w->sunDirectPipeline];[r setCullMode:MTLCullModeNone];
    [r setViewport:(MTLViewport){0,0,w->sunRaw.width,w->sunRaw.height,0,1}];
    [r setFragmentBytes:w->sunTemporalFrame length:160 atIndex:0];[r setFragmentBytes:w->sunFrame length:144 atIndex:1];
    [r setFragmentBytes:w->sunBlendFrame length:320 atIndex:2];[r setFragmentBytes:w->sunGlassParams length:sizeof w->sunGlassParams atIndex:3];
    if(w->sunFog)[r setFragmentBuffer:w->sunFog offset:w->sunFogOffset atIndex:24];
    else{uint8_t fog[64]={0};[r setFragmentBytes:fog length:64 atIndex:24];}
    [r setFragmentTexture:w->sunRaw atIndex:0];[r setFragmentTexture:w->sunInputDepth atIndex:1];[r setFragmentTexture:w->sunMemoryActive?nil:w->sunSceneCopy atIndex:2];
    [r setFragmentSamplerState:w->sunSampler atIndex:13];
    for(int i=0;i<4;i++) {
        id<MTLTexture> map=i==0?w->sunMap:i==w->sunCascaded-1?w->sunSecondMap:i<3?w->sunMiddleMaps[i-1]:nil;
        [r setFragmentTexture:map?:w->sunMap atIndex:20+i];[r setFragmentTexture:w->sunGlassDepth[i]?:w->sunMap atIndex:24+i];
        [r setFragmentTexture:w->sunGlassColor[i]?:w->sunGlassWhite atIndex:28+i];
    }
    sunLightingBind(w,r);
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];return 0;
}
