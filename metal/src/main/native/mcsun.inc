// Native sun visibility interpolation. Cached light samples avoid re-rendering unchanged terrain at every frame.
#import <QuartzCore/QuartzCore.h>
#include "mcsun_geometry.h"
id<MTLTexture> mco_sun_debug_map(Own *w) { return w->sunMap; }
id<MTLTexture> mco_sun_debug_far_map(Own *w) { return w->sunSecondMap; }
id<MTLTexture> mco_sun_debug_cascade_map(Own *w,int i) { return i==0?w->sunMap:i==w->sunCascaded-1?w->sunSecondMap:i>0&&i<3?w->sunMiddleMaps[i-1]:nil; }
void mco_sun_debug_stats(Own *w,uint64_t *stats) { stats[0]=w->sunRenders;stats[1]=w->sunCacheHits; }

id<MTLBuffer> mco_sun_debug_draw_args(Own *w) { return w->sunArgs; }
int mco_sun_debug_exact(Own *w) { return w->sunExactDraw; }
void mco_sun_debug_force_legacy(Own *w,int force) { w->sunForceLegacy=force!=0; }
int mco_sun_culling(Own *w,const OwnSunCulling *data) {
    if(!data||data->count>16||!isfinite(data->nearRadius)||data->nearRadius<0||data->nearRadius>16)return -1;
    for(uint32_t i=0;i<data->count;i++)for(int k=0;k<4;k++)if(!isfinite(data->planes[i][k]))return -1;
    w->sunCulling=*data;return 0;
}
static BOOL sunCasterVisible(Own *w,simd_float3 lo,simd_float3 hi) {
    if(!w->sunCulling.count)return YES;
    if(simd_any(lo>hi)||!isfinite(simd_reduce_add(lo)+simd_reduce_add(hi)))return YES;
    simd_float3 nearest=simd_clamp((simd_float3){0,0,0},lo,hi);
    if(simd_length_squared(nearest)<=w->sunCulling.nearRadius*w->sunCulling.nearRadius)return YES;
    for(uint32_t i=0;i<w->sunCulling.count;i++) {
        simd_float4 p=w->sunCulling.planes[i];simd_float3 vertex=simd_select(lo,hi,p.xyz>=0);
        if(simd_dot(p.xyz,vertex)+p.w<0)return NO;
    }
    return YES;
}

int mco_sun_casters(Own *w,const void *data,int count) {
    if(count<0||count>4096||(!data&&count))return -1;
    if(count>w->sunCasterCapacity) {
        OwnSunCaster *next=realloc(w->sunCasters,(size_t)count*sizeof(OwnSunCaster));if(!next)return -1;
        w->sunCasters=next;w->sunCasterCapacity=count;
    }
    if(count)memcpy(w->sunCasters,data,(size_t)count*sizeof(OwnSunCaster));w->sunCasterCount=count;return count;
}

// Candidate storage follows the terrain table's existing frame ring.
int mco_sun_candidates(Own *w,id<MTLBuffer> buffer,int count) {
    if(count< -1 || (count>=0 && (!buffer || (NSUInteger)count>buffer.length/4)))return -1;
    w->sunCandidates=count<0?nil:buffer;w->sunCandidateCount=MAX(0,count);return 0;
}
int mco_sun_receivers(Own *w,id<MTLBuffer> buffer,int count) {
    if(count< -1 || (count>=0 && (!buffer || (NSUInteger)count>buffer.length/32)))return -1;
    w->sunTransparentReceivers=count<0?nil:buffer;w->sunTransparentReceiverCount=MAX(0,count);return 0;
}
id<MTLBuffer> mco_sun_debug_receiver_stats(Own *w) { return w->sunReceiverStats; }
id<MTLBuffer> mco_sun_debug_receiver_bins(Own *w) { return w->sunReceiverBins; }
// Applied by the render thread before frame encoding. A mode change never reuses the other filter's history.
void mco_sun_filter_optimization(Own *w,int enabled) {
    int reference=enabled?0:1;
    if(w->sunFilterReference!=reference){w->sunFilterReference=reference;w->sunTemporalValid=0;}
}
void mco_sun_memory_optimization(Own *w,int enabled) { w->sunMemoryReference=!enabled; }
void mco_sun_half_shadows(Own *w,int enabled) { w->sunHalfMask=enabled!=0; }
// The sun.18.11 shadow fixes, each switchable: plane comparison (flicker) and contact (bounded offsets, no raster slope bias).
void mco_sun_shadow_fixes(Own *w,int plane,int contact) { w->sunLegacyPlane=!plane;w->sunLegacyContact=!contact; }
void mco_sun_debug_memory_fallback(Own *w,int force) { w->sunForceMemoryFallback=force!=0; }
int mco_sun_debug_memory_active(Own *w) { return w->sunMemoryActive; }
int mco_sun_debug_memory_depth_direct(Own *w) { return w->sunMemoryDepthDirect; }
int mco_sun_debug_copied_inputs(Own *w) { return (w->sunSceneCopy?1:0)|(w->sunDepthCopy?2:0); }
int mco_sun_debug_memory_copy_mask(Own *w) { return w->sunMemoryCopyMask; }
id<MTLTexture> mco_sun_debug_history(Own *w) { return w->sunHistory[w->sunHistoryIndex]; }
void mco_sun_receiver_optimization(Own *w,int enabled) { w->sunReceiverReference=!enabled; }
int mco_sun_debug_receiver_optimized(Own *w) { return w->sunLastReceiverOptimized; }
int mco_sun_debug_receiver_ready(Own *w) { return w->sunReceiverReady; }
void mco_sun_selection_optimization(Own *w,int enabled) { w->sunSelectionDisabled=!enabled; }
// Off by default natively; the game applies the setting before every frame.
void mco_sun_shadow_map_optimization(Own *w,int enabled) { w->sunDepthSplit=enabled!=0; }
int mco_sun_debug_depth_split(Own *w) { return w->sunLastDepthSplit; }
void mco_sun_geometry_optimization(Own *w,int enabled) { w->sunIndexedEnabled=enabled!=0; }
int mco_sun_debug_indexed(Own *w) { return w->sunLastIndexed; }
// Fixtures that read the selection counters collect them on every frame.
void mco_sun_debug_force_stats(Own *w,int force) { w->sunForceStats=force!=0; }
// -Dsiliconshaders.sun.diagnostics: counters plus the receiver-culling console line every 2 s. Off by default.
void mco_sun_diagnostics(Own *w,int enabled) { w->sunDiagnostics=enabled!=0; }
void mco_sun_debug_force_grouped_cull(Own *w,int force) { w->sunForceGroupedCull=force!=0; }
int mco_sun_debug_grouped_cull(Own *w) { return w->sunLastGroupedCull; }
int mco_sun_debug_direct_resolve(Own *w) { return w->sunLastDirectResolve; }
void mco_sun_debug_force_separate_resolve(Own *w,int force) { w->sunForceSeparateResolve=force!=0; }
int mco_sun_debug_deferred(Own *w) { return w->sunDeferred; }
static int sunInit(Own *w,char *err,int cap) {
    NSError *error=nil;
    if(!w->sunPipeline) {
        id<MTLFunction> reset=[w->library newFunctionWithName:@"sun_reset"],cull=[w->library newFunctionWithName:@"sun_cull"];
        w->sunReset=reset?[w->ctx->device newComputePipelineStateWithFunction:reset error:&error]:nil;
        w->sunCull=cull?[w->ctx->device newComputePipelineStateWithFunction:cull error:&error]:nil;
        [reset release];[cull release];
        MTLFunctionConstantValues *cv=[[[MTLFunctionConstantValues alloc] init] autorelease];bool compact=w->compact!=0;
        [cv setConstantValue:&compact type:MTLDataTypeBool atIndex:1];
        id<MTLFunction> vertex=[w->library newFunctionWithName:@"sun_vs" constantValues:cv error:&error];
        id<MTLFunction> fragment=[w->library newFunctionWithName:@"sun_fs"];
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
        pd.label=@"siliconshaders native sunlight: shadow map";pd.vertexFunction=vertex;pd.fragmentFunction=fragment;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        w->sunPipeline=vertex&&fragment?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
        [vertex release];[fragment release];
        MTLDepthStencilDescriptor *dd=[[[MTLDepthStencilDescriptor alloc] init] autorelease];dd.depthCompareFunction=MTLCompareFunctionLessEqual;dd.depthWriteEnabled=YES;
        w->sunDepth=[w->ctx->device newDepthStencilStateWithDescriptor:dd];
        MTLSamplerDescriptor *sd=[[[MTLSamplerDescriptor alloc] init] autorelease];sd.minFilter=MTLSamplerMinMagFilterLinear;sd.magFilter=MTLSamplerMinMagFilterLinear;
        sd.compareFunction=MTLCompareFunctionLessEqual;sd.sAddressMode=MTLSamplerAddressModeClampToEdge;sd.tAddressMode=MTLSamplerAddressModeClampToEdge;
        w->sunSampler=[w->ctx->device newSamplerStateWithDescriptor:sd];
        if(!w->sunReset||!w->sunCull||!w->sunPipeline||!w->sunDepth||!w->sunSampler) {
            ownErr(error,"native sun pipeline",err,cap);
            [w->sunReset release];w->sunReset=nil;[w->sunCull release];w->sunCull=nil;
            [w->sunPipeline release];w->sunPipeline=nil;[w->sunDepth release];w->sunDepth=nil;
            [w->sunSampler release];w->sunSampler=nil;return -1;
        }
    }
    if(!w->sunExactPipeline && !w->sunExactUnavailable) {
        NSError *issue=nil;
        id<MTLFunction> reset=[w->library newFunctionWithName:@"sun_reset_exact"],cull=[w->library newFunctionWithName:@"sun_cull_exact"];
        w->sunResetExact=reset?[w->ctx->device newComputePipelineStateWithFunction:reset error:&issue]:nil;
        w->sunCullExact=cull?[w->ctx->device newComputePipelineStateWithFunction:cull error:&issue]:nil;
        [reset release];[cull release];
        MTLFunctionConstantValues *cv=[[[MTLFunctionConstantValues alloc] init] autorelease];bool compact=w->compact!=0;
        [cv setConstantValue:&compact type:MTLDataTypeBool atIndex:1];
        id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_exact_vs" constantValues:cv error:&issue];
        id<MTLFunction> fs=[w->library newFunctionWithName:@"sun_fs"];
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
        pd.label=@"siliconshaders sunlight: compact real quads";pd.vertexFunction=vs;pd.fragmentFunction=fs;
        pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        w->sunExactPipeline=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue]:nil;
        [vs release];[fs release];
        if(!w->sunResetExact||!w->sunCullExact||!w->sunExactPipeline) {
            [w->sunResetExact release];w->sunResetExact=nil;[w->sunCullExact release];w->sunCullExact=nil;
            [w->sunExactPipeline release];w->sunExactPipeline=nil;w->sunExactUnavailable=1;
            fprintf(stderr,"[siliconshaders sun] Compact geometry pipeline unavailable; using record draws\n");
        }
    }
    if(!w->sunActorPipeline) {
        id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_actor_vs"],fs=[w->library newFunctionWithName:@"sun_actor_fs"];
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
        pd.label=@"siliconshaders entity shadow (reused geometry)";pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        w->sunActorPipeline=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
        [vs release];[fs release];
        if(!w->sunActorPipeline){ownErr(error,"entity shadow pipeline",err,cap);return -1;}
    }
    return 0;
}

static id<MTLTexture> sunTexture(Own *w,int size,char *err,int cap) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:size height:size mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> texture=[w->ctx->device newTextureWithDescriptor:td];
    if(!texture)snprintf(err,cap,"Shadow texture allocation failed");return texture;
}
static void sunClearCache(Own *w) {
    for(int i=0;i<2;i++) { [w->sunCached[i] release];w->sunCached[i]=nil;w->sunValid[i]=0;[w->sunMiddleMaps[i] release];w->sunMiddleMaps[i]=nil; }
}
// Optional compute variant. Unsupported widths, compilation or allocation retain the serial selector.
static void sunGroupedInit(Own *w) {
    if(w->sunGroupedInitialized)return;
    w->sunGroupedInitialized=1;
    @autoreleasepool {
        NSError *error=nil;
        const char *names[]={"sun_reset_select","sun_reset_select_exact","sun_select_sections","sun_select_sections_exact","sun_cull_selected","sun_cull_selected_exact"};
        id<MTLComputePipelineState> *targets[]={&w->sunSelectReset[0],&w->sunSelectReset[1],&w->sunSelectSections[0],&w->sunSelectSections[1],&w->sunCullSelected[0],&w->sunCullSelected[1]};
        for(int i=0;i<6;i++) {
            id<MTLFunction> f=[w->library newFunctionWithName:[NSString stringWithUTF8String:names[i]]];
            *targets[i]=f?[w->ctx->device newComputePipelineStateWithFunction:f error:&error]:nil;[f release];
            if(!*targets[i] || (*targets[i]).threadExecutionWidth!=32 || (*targets[i]).maxTotalThreadsPerThreadgroup<64) {
                for(int k=0;k<6;k++){[*targets[k] release];*targets[k]=nil;}
                w->sunGroupedUnavailable=1;
                fprintf(stderr,"[siliconshaders sun] Parallel caster selection unavailable; retaining serial selection\n");return;
            }
        }
    }
}

// A discard-capable fragment function keeps Apple GPUs from rejecting hidden fragments early,
// for every caster drawn with it. Opaque casters get a depth-only pipeline; only alpha-tested
// casters keep sun_fs. Any failure retains the single combined draw.
static BOOL sunSplitInit(Own *w) {
    if(w->sunSplitTried)return !w->sunSplitUnavailable;
    w->sunSplitTried=1;
    @autoreleasepool {
        NSError *error=nil;
        MTLFunctionConstantValues *cv=[[[MTLFunctionConstantValues alloc] init] autorelease];bool compact=w->compact!=0;
        [cv setConstantValue:&compact type:MTLDataTypeBool atIndex:1];
        id<MTLFunction> fs=[w->library newFunctionWithName:@"sun_fs"];
        const char *opaque[]={"sun_vs","sun_exact_vs"},*cutout[]={"sun_cutout_vs","sun_exact_cutout_vs"};
        for(int exact=0;exact<2;exact++) {
            id<MTLFunction> depthVs=[w->library newFunctionWithName:[NSString stringWithUTF8String:opaque[exact]] constantValues:cv error:&error];
            id<MTLFunction> cutoutVs=[w->library newFunctionWithName:[NSString stringWithUTF8String:cutout[exact]] constantValues:cv error:&error];
            MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
            pd.label=@"siliconshaders sunlight: opaque casters (depth only)";pd.vertexFunction=depthVs;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
            w->sunDepthOnly[exact]=depthVs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
            pd.label=@"siliconshaders sunlight: alpha-tested casters";pd.vertexFunction=cutoutVs;pd.fragmentFunction=fs;
            w->sunCutout[exact]=cutoutVs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
            [depthVs release];[cutoutVs release];
            if(!w->sunDepthOnly[exact]||!w->sunCutout[exact])w->sunSplitUnavailable=1;
        }
        [fs release];
        if(w->sunSplitUnavailable) {
            for(int i=0;i<2;i++){[w->sunDepthOnly[i] release];w->sunDepthOnly[i]=nil;[w->sunCutout[i] release];w->sunCutout[i]=nil;}
            fprintf(stderr,"[siliconshaders sun] Depth-only shadow casters unavailable; retaining combined draw\n");
        }
    }
    return !w->sunSplitUnavailable;
}

// Exact quads drawn as 4 indexed corners (sun_exact_indexed_vs). Needs the split's cutout
// function for the third pipeline only; any failure retains the 6-vertex list draws.
static BOOL sunIndexedInit(Own *w) {
    if(w->sunIndexedTried)return !w->sunIndexedUnavailable;
    w->sunIndexedTried=1;
    @autoreleasepool {
        NSError *error=nil;
        MTLFunctionConstantValues *cv=[[[MTLFunctionConstantValues alloc] init] autorelease];bool compact=w->compact!=0;
        [cv setConstantValue:&compact type:MTLDataTypeBool atIndex:1];
        id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_exact_indexed_vs" constantValues:cv error:&error];
        id<MTLFunction> cutoutVs=[w->library newFunctionWithName:@"sun_exact_indexed_cutout_vs" constantValues:cv error:&error];
        id<MTLFunction> fs=[w->library newFunctionWithName:@"sun_fs"],args=[w->library newFunctionWithName:@"sun_indexed_args"];
        MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        pd.label=@"siliconshaders sunlight: indexed quads";pd.vertexFunction=vs;pd.fragmentFunction=fs;
        w->sunIndexed[0]=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
        pd.label=@"siliconshaders sunlight: indexed opaque casters (depth only)";pd.fragmentFunction=nil;
        w->sunIndexed[1]=vs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
        pd.label=@"siliconshaders sunlight: indexed alpha-tested casters";pd.vertexFunction=cutoutVs;pd.fragmentFunction=fs;
        w->sunIndexed[2]=cutoutVs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&error]:nil;
        w->sunIndexedArgs=args?[w->ctx->device newComputePipelineStateWithFunction:args error:&error]:nil;
        [vs release];[cutoutVs release];[fs release];[args release];
        // Quad k: corners 4k+0,1,2 and 4k+2,3,0, the same triangles as the list draw's 0x032210 order.
        enum { QUADS=2048 };
        uint16_t indices[QUADS*6];
        for(int q=0;q<QUADS;q++){const uint16_t corner[]={0,1,2,2,3,0};for(int k=0;k<6;k++)indices[q*6+k]=(uint16_t)(q*4+corner[k]);}
        w->sunQuadIndices=[w->ctx->device newBufferWithBytes:indices length:sizeof indices options:MTLResourceStorageModeShared];
        if(!w->sunIndexed[0]||!w->sunIndexed[1]||!w->sunIndexed[2]||!w->sunIndexedArgs||!w->sunQuadIndices) {
            for(int i=0;i<3;i++){[w->sunIndexed[i] release];w->sunIndexed[i]=nil;}
            [w->sunIndexedArgs release];w->sunIndexedArgs=nil;[w->sunQuadIndices release];w->sunQuadIndices=nil;
            w->sunIndexedUnavailable=1;fprintf(stderr,"[siliconshaders sun] Indexed shadow geometry unavailable; retaining list draws\n");
        }
    }
    return !w->sunIndexedUnavailable;
}

// SUN_ENCODE_TERRAIN fills the far-map cache: terrain only, without view-dependent selection
// (receiver grid, swept-frustum planes, CPU candidates), so it stays valid while the camera turns.
// SUN_ENCODE_ACTORS copies base (that cache) into target and draws only entity casters on top.
enum { SUN_ENCODE_ALL, SUN_ENCODE_TERRAIN, SUN_ENCODE_ACTORS };
static int sunEncodeMode(Own *w,Enc *enc,const void *source,id<MTLTexture> target,id<MTLBuffer> arena,id<MTLBuffer> sections,
    id<MTLBuffer> recs,id<MTLTexture> atlas,id<MTLSamplerState> sampler,int level,int mode,id<MTLTexture> base,char *err,int cap) {
    uint8_t frame[144];memcpy(frame,source,144);uint32_t *v=(uint32_t *)frame;
    v[33]=(uint32_t)(recs.length/32);v[32]=MIN(v[32],(uint32_t)(sections.length/32));
    BOOL draw=v[34]>0;
    uint64_t exactBytes=ownSunExactBytes(v[33],w->ctx->device.maxBufferLength);
    BOOL exact=draw&&exactBytes&&!w->sunForceLegacy&&!w->sunExactUnavailable&&w->sunExactPipeline;
    if(exact && (!w->sunList || w->sunList.length<exactBytes)) {
        id<MTLBuffer> list=[w->ctx->device newBufferWithLength:(NSUInteger)exactBytes options:MTLResourceStorageModePrivate];
        if(list) { [w->sunList release];w->sunList=list; }
        else {
            // Allocation failure must not drop geometry, retry every frame, or disable sunlight.
            exact=NO;w->sunExactUnavailable=1;
            fprintf(stderr,"[siliconshaders sun] Compact geometry allocation unavailable; using record draws\n");
        }
    }
    if(draw && (!w->sunList || w->sunList.length<(NSUInteger)v[33]*sizeof(uint32_t))) {
        id<MTLBuffer> list=[w->ctx->device newBufferWithLength:MAX((NSUInteger)16,(NSUInteger)v[33]*sizeof(uint32_t)) options:MTLResourceStorageModePrivate];
        if(!list) { snprintf(err,cap,"Shadow list allocation failed");return -1; }
        [w->sunList release];w->sunList=list;
    }
    BOOL full=mode==SUN_ENCODE_TERRAIN;
    uint32_t threads=w->sunCandidates&&!full?(uint32_t)w->sunCandidateCount:v[32];
    BOOL grouped=draw&&!w->sunSelectionDisabled&&(threads>=32||w->sunForceGroupedCull);
    if(grouped) {
        sunGroupedInit(w);grouped=!w->sunGroupedUnavailable;
        NSUInteger bytes=MAX((NSUInteger)16,(NSUInteger)threads*4);
        if(grouped && (!w->sunSelectedSections || w->sunSelectedSections.length<bytes)) {
            id<MTLBuffer> selected=[w->ctx->device newBufferWithLength:bytes options:MTLResourceStorageModePrivate];
            if(selected){[w->sunSelectedSections release];w->sunSelectedSections=selected;}
            else {grouped=NO;w->sunGroupedUnavailable=1;}
        }
    }
    BOOL split=draw&&w->sunDepthSplit&&sunSplitInit(w);
    BOOL indexed=exact&&w->sunIndexedEnabled&&sunIndexedInit(w);
    // Debug state describes regular cascade draws, not far-cache refreshes or entity layers.
    if(mode==SUN_ENCODE_ALL) {
        w->sunLastGroupedCull=grouped;
        w->sunExactDraw=exact;w->sunLastDepthSplit=split;w->sunLastIndexed=indexed;
    }
    // Bytes 16-31 provide a valid dummy binding if optional surface allocation fails;
    // 32-47 hold the grouped dispatch, 48-63 the alpha-tested draw of the split,
    // 64-83 and 96-115 the indexed forms of the opaque and alpha-tested draws.
    if(!w->sunArgs)w->sunArgs=[w->ctx->device newBufferWithLength:128 options:MTLResourceStorageModePrivate];
    if(!w->sunArgs) { snprintf(err,cap,"Shadow draw allocation failed");return -1; }
    mc_pre_end_encoders(enc);id<MTLCommandBuffer> command=w->sunDeferred?mc_frame_cmd(enc):mc_pre(enc);
    BOOL terrain=draw&&mode!=SUN_ENCODE_ACTORS,actors=draw&&mode!=SUN_ENCODE_TERRAIN&&w->sunCasterCount;
    if(mode==SUN_ENCODE_ACTORS) {
        id<MTLBlitCommandEncoder> copy=mc_profiled_blit(enc,command);copy.label=enc->samples?[NSString stringWithFormat:@"sun far terrain cache to level %d",level]:@"sun far terrain cache";
        [copy copyFromTexture:base sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(base.width,base.height,1)
            toTexture:target destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
        [copy endEncoding];[copy release];
    }
    if(terrain || (actors&&w->sunDeferred)) {
        id<MTLComputeCommandEncoder> c=mc_profiled_compute(enc,command,MTLDispatchTypeSerial);c.label=!enc->samples?@"sun caster cull":[NSString stringWithFormat:@"sun caster cull level %d (%u)%s",level,v[34],grouped?" [parallel]":""];
        OwnSunCulling none={0};
        [c setBytes:frame length:144 atIndex:0];[c setBuffer:w->sunArgs offset:0 atIndex:3];
        [c setBytes:full?&none:&w->sunCulling length:sizeof w->sunCulling atIndex:5];
        OwnSunSurfaceCull surface={.enabled=w->sunDeferred&&w->sunReceiverReady&&!full,
            .candidateCount=w->sunCandidates&&!full?(uint32_t)w->sunCandidateCount:UINT32_MAX,.grid={(uint32_t)level,split?1u:0u,w->sunReceiverReady&&w->sunStatsFrame&&!full?1u:0u,0}};
        [c setBuffer:w->sunReceiverBins?:w->sunArgs offset:w->sunReceiverBins?0:16 atIndex:6];
        [c setBytes:&surface length:sizeof surface atIndex:7];
        [c setBuffer:w->sunCandidates?:w->sunArgs offset:0 atIndex:8];
        [c setBuffer:w->sunReceiverStats?:w->sunArgs offset:w->sunReceiverStats?level*32:16 atIndex:9];
        if(terrain) {
        [c setComputePipelineState:grouped?w->sunSelectReset[exact?1:0]:exact?w->sunResetExact:w->sunReset];
        [c dispatchThreads:MTLSizeMake(16,1,1) threadsPerThreadgroup:MTLSizeMake(16,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
        [c setComputePipelineState:grouped?w->sunSelectSections[exact?1:0]:exact?w->sunCullExact:w->sunCull];
        [c setBuffer:sections offset:0 atIndex:1];[c setBuffer:recs offset:0 atIndex:2];[c setBuffer:w->sunList offset:0 atIndex:4];
        if(grouped) {
            [c setBuffer:w->sunSelectedSections offset:0 atIndex:10];
            if(threads)[c dispatchThreadgroups:MTLSizeMake((threads+63u)/64u,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
            [c memoryBarrierWithScope:MTLBarrierScopeBuffers];
            [c setComputePipelineState:w->sunCullSelected[exact?1:0]];
            [c dispatchThreadgroupsWithIndirectBuffer:w->sunArgs indirectBufferOffset:32 threadsPerThreadgroup:MTLSizeMake(64,1,1)];
        } else if(threads)[c dispatchThreads:MTLSizeMake(threads,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
        if(indexed) {
            [c memoryBarrierWithScope:MTLBarrierScopeBuffers];[c setComputePipelineState:w->sunIndexedArgs];
            [c dispatchThreads:MTLSizeMake(2,1,1) threadsPerThreadgroup:MTLSizeMake(2,1,1)];
        }
        }
        if(actors&&w->sunDeferred) {
            [c setComputePipelineState:w->sunReceiverActors];
            uint32_t count=w->sunCasterCount;[c setBytes:&count length:4 atIndex:11];
            [c setBuffer:w->sunActorData offset:0 atIndex:10];
            [c setBuffer:w->sunActorArgs offset:(NSUInteger)level*count*32 atIndex:12];
            [c dispatchThreads:MTLSizeMake(count,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
        }
        [c endEncoding];[c release];
    }
    MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.depthAttachment.texture=target;
    rp.depthAttachment.loadAction=mode==SUN_ENCODE_ACTORS?MTLLoadActionLoad:MTLLoadActionClear;rp.depthAttachment.clearDepth=1;rp.depthAttachment.storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,rp);
    if(!r) { snprintf(err,cap,"Cannot begin shadow map");return -1; }
    r.label=!enc->samples?@"sun shadow map":[NSString stringWithFormat:@"sun shadow map level %d (%u)%s",level,v[34],mode==SUN_ENCODE_TERRAIN?" [far cache refresh]":mode==SUN_ENCODE_ACTORS?" [cached terrain + entities]":""];
    // Receiver-plane comparisons handle slope per depth texel. A raster slope bias grows with coarse-map
    // texels and can bury one-block walls/roofs; retain only a bounded numeric constant bias. With the contact
    // fix switched off (mco_sun_shadow_fixes) the previous slope bias returns.
    if(terrain) {
        [r setRenderPipelineState:indexed?w->sunIndexed[split?1:0]:split?w->sunDepthOnly[exact?1:0]:exact?w->sunExactPipeline:w->sunPipeline];[r setDepthStencilState:w->sunDepth];[r setCullMode:MTLCullModeNone];[r setDepthBias:1 slopeScale:w->sunLegacyContact?1.5f:0 clamp:w->sunLegacyContact?0.005f:0.00001f];
        [r setVertexBytes:frame length:144 atIndex:0];[r setVertexBuffer:arena offset:0 atIndex:1];
        [r setVertexBuffer:sections offset:0 atIndex:2];[r setVertexBuffer:recs offset:0 atIndex:3];[r setVertexBuffer:w->sunList offset:0 atIndex:4];
        [r setFragmentTexture:atlas atIndex:0];[r setFragmentSamplerState:sampler atIndex:0];
        if(indexed) {
            [r setVertexBuffer:w->sunArgs offset:0 atIndex:5];
            [r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:w->sunQuadIndices indexBufferOffset:0 indirectBuffer:w->sunArgs indirectBufferOffset:64];
        } else [r drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:w->sunArgs indirectBufferOffset:0];
        if(split) {
            // Opaque depth first: later alpha-tested fragments behind it fail the depth test.
            [r setRenderPipelineState:indexed?w->sunIndexed[2]:w->sunCutout[exact?1:0]];
            if(indexed)[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:w->sunQuadIndices indexBufferOffset:0 indirectBuffer:w->sunArgs indirectBufferOffset:96];
            else [r drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:w->sunArgs indirectBufferOffset:48];
        }
    }
    if(actors) {
        [r setRenderPipelineState:w->sunActorPipeline];[r setDepthStencilState:w->sunDepth];[r setDepthBias:1 slopeScale:w->sunLegacyContact?1.5f:0 clamp:w->sunLegacyContact?0.005f:0.00001f];[r setCullMode:MTLCullModeNone];
        [r setVertexBytes:frame length:144 atIndex:0];
        for(int i=0;i<w->sunCasterCount;i++) {
            OwnSunCaster *batch=w->sunCasters+i;if(batch->config.x>0.5f||batch->draw.x<=0)continue;
            if(!sunCasterVisible(w,batch->boundsLo.xyz,batch->boundsHi.xyz))continue;
            [r setVertexBuffer:(id<MTLBuffer>)(uintptr_t)batch->vertices offset:0 atIndex:1];
            [r setVertexBytes:batch length:sizeof *batch atIndex:5];[r setFragmentBytes:batch length:sizeof *batch atIndex:5];
            [r setFragmentTexture:(id<MTLTexture>)(uintptr_t)batch->texture atIndex:0];
            [r setFragmentSamplerState:(id<MTLSamplerState>)(uintptr_t)batch->sampler atIndex:0];
            if(w->sunDeferred) {
                NSUInteger offset=((NSUInteger)level*w->sunCasterCount+i)*32;
                if(batch->config.y>0.5f)[r drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:w->sunActorArgs indirectBufferOffset:offset];
                else [r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:batch->draw.w?MTLIndexTypeUInt32:MTLIndexTypeUInt16
                    indexBuffer:(id<MTLBuffer>)(uintptr_t)batch->indices indexBufferOffset:(NSUInteger)batch->draw.y*(batch->draw.w?4:2)
                    indirectBuffer:w->sunActorArgs indirectBufferOffset:offset];
            } else if(batch->config.y>0.5f)[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:(batch->draw.x/4)*6];
            else [r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:batch->draw.x indexType:batch->draw.w?MTLIndexTypeUInt32:MTLIndexTypeUInt16
                indexBuffer:(id<MTLBuffer>)(uintptr_t)batch->indices indexBufferOffset:(NSUInteger)batch->draw.y*(batch->draw.w?4:2) instanceCount:1 baseVertex:batch->draw.z baseInstance:0];
        }
    }
    [r endEncoding];[r release];w->sunRenders++;return 0;
}
static int sunEncode(Own *w,Enc *enc,const void *source,id<MTLTexture> target,id<MTLBuffer> arena,id<MTLBuffer> sections,
    id<MTLBuffer> recs,id<MTLTexture> atlas,id<MTLSamplerState> sampler,int level,char *err,int cap) {
    return sunEncodeMode(w,enc,source,target,arena,sections,recs,atlas,sampler,level,SUN_ENCODE_ALL,nil,err,cap);
}
#include "mcsun_far.inc"
#include "mcsun_tuning.inc"
#include "mcblocklight.inc"
int mco_sun_frame(Own *w,Enc *enc,const void *frame,int length,id<MTLBuffer> arena,id<MTLBuffer> sections,
    id<MTLBuffer> recs,id<MTLTexture> atlas,id<MTLSamplerState> sampler,char *err,int cap) {
    // These FFM entry points have no caller-owned Cocoa autorelease pool.
    // Release temporary pass descriptors here, including their counter-buffer attachments.
    @autoreleasepool {
    w->sunEntityTarget=enc->colorCount==1?enc->colors[0]:nil;
    w->sunActive=0;w->sunExactDraw=0;w->sunLastGroupedCull=0;w->sunLastDepthSplit=0;w->sunLastIndexed=0;w->sunGbuffer=0;w->sunLastLighting=0;
    w->sunStatsFrame=w->sunForceStats||(w->sunDiagnostics&&CACurrentMediaTime()-w->sunReceiverLogTime>=2);w->sunLastReceiverOptimized=0;w->sunMemoryActive=0;w->sunMemoryDepthDirect=0;w->sunMemoryCopyMask=0;w->sunLastDirectResolve=0;w->sunDeferred=0;w->sunReceiverReady=0;memset(w->sunBlendFrame,0,sizeof w->sunBlendFrame);
    if((length!=144&&length!=464&&length!=256&&length!=400&&length!=688)||!frame) { snprintf(err,cap,"Invalid sun frame");return -1; }
    // A private copy: the far-map cache may rewrite the far level's light clip (mcsun_far.inc).
    uint8_t packet[688];memcpy(packet,frame,length);frame=packet;
    memcpy(w->sunFrame,frame,144);const uint8_t *bytes=frame;const uint32_t *v=frame;
    // No previous-frame depth or exact-camera gate. Receiver bins are rebuilt after opaque terrain.
    for(int i=0;i<w->sunCasterCount;i++)if(w->sunCasters[i].config.x<0.5f) { *(uint32_t *)(w->sunFrame+140)|=2u;break; }
    // Shadow fixes switched off (mco_sun_shadow_fixes): bit 8 legacy shared-depth comparison, bit 16 uncapped normal offset.
    if(w->sunLegacyPlane)*(uint32_t *)(w->sunFrame+140)|=8u;
    if(w->sunLegacyContact)*(uint32_t *)(w->sunFrame+140)|=16u;
    if(!v[35]) { if(w->sunReceiverMode){fprintf(stderr,"[siliconshaders sun] receiver culling: disabled\n");w->sunReceiverMode=0;} [w->sunBaseMap release];w->sunBaseMap=nil;sunClearCache(w);sunFarDrop(w);w->sunMap=w->sunSecondMap=nil;w->sunBlending=0;w->sunSize=0;sunTemporalClear(w);sunGlassClear(w);return 0; }
    uint32_t requested=v[34];
    if(requested!=0&&requested!=512&&requested!=1024&&requested!=2048&&requested!=4096) { snprintf(err,cap,"Unsupported shadow size");return -1; }
    if(sunInit(w,err,cap)<0)return -1;
    if(mco_blocklight_run(w,enc,err,cap)<0)return -1;
    int cascaded=requested>0?(length==688?*(const uint32_t *)(bytes+252):length==400?2:0):0;
    if(length==688&&requested>0&&(cascaded<2||cascaded>4)){snprintf(err,cap,"Invalid shadow level count");return -1;}
    int previousCascades=w->sunCascaded;
    if(cascaded!=w->sunCascaded)w->sunTemporalValid=0;
    w->sunCascaded=cascaded;
    // Zero swept-frustum planes can mean a valid unbounded caster volume.
    // The explicit receiver setting, not the coarse plane count, controls this stage.
    // Nether/End still need deferred material light and a background resolve with shadows disabled.
    BOOL dimensionLighting=length==688&&w->sunSky[3]!=0;
    BOOL defer=(cascaded||dimensionLighting) && (w->sunSurface.current.enabled || (w->sunSurface.current.flags&OWN_SUN_DEFERRED_LIGHTING)) &&
        (length==688 || length==400) && enc->colorCount==1 && enc->render && enc->colors[0] && enc->depth &&
        enc->depth.pixelFormat==MTLPixelFormatDepth32Float && enc->depth.sampleCount==1 && enc->colors[0].sampleCount==1 &&
        enc->depth.storageMode!=MTLStorageModeMemoryless && enc->colors[0].storageMode!=MTLStorageModeMemoryless;
    if(defer && !sunReceiverPrepare(w))defer=NO;
    BOOL blend=length==464&&requested>0&&*(const uint32_t *)(bytes+436)!=0;
    if(cascaded) {
        const uint8_t *far=bytes+256+(cascaded-2)*144;
        for(int i=1;i<cascaded;i++) {
            const uint8_t *sample=bytes+256+(i-1)*144;const uint32_t *sv=(const uint32_t *)sample;
            const uint8_t *previous=i==1?bytes:sample-144;
            uint32_t size=sv[34];
            if((size!=256&&size!=512&&size!=1024&&size!=2048&&size!=4096)||size>*(const uint32_t *)(previous+136)
                || !sv[35] || memcmp(bytes+96,sample+96,16) || *(const float *)(sample+116)!=1.0f/size
                || !isfinite(*(const float *)(sample+112)) || *(const float *)(sample+112)<=*(const float *)(previous+112)) {
                snprintf(err,cap,"Invalid shadow cascade");return -1;
            }
        }
        if(sunFarPrepare(w,packet,length,cascaded,atlas,err,cap)<0)return -1;
        // Each level renders the physical sun. Near coverage/resolution and filtering are unchanged.
        for(int i=0;i<cascaded;i++) {
            const uint8_t *sample=i?bytes+256+(i-1)*144:bytes;uint32_t size=*(const uint32_t *)(sample+136);
            id<MTLTexture> *map=i==0?&w->sunBaseMap:i==cascaded-1?&w->sunCached[0]:&w->sunMiddleMaps[i-1];
            if(!*map||(*map).width!=size) {
                id<MTLTexture> next=sunTexture(w,size,err,cap);if(!next)return -1;
                [*map release];*map=next;w->sunTemporalValid=0;
            }
            if(!defer && sunEncodeCascade(w,enc,sample,*map,arena,sections,recs,atlas,sampler,i,err,cap)<0)return -1;
            if(i>0&&i<cascaded-1){memcpy(w->sunBlendFrame+160+(i-1)*64,sample,64);memcpy(w->sunBlendFrame+288+(i-1)*16,sample+112,16);}
        }
        for(int i=MAX(0,cascaded-2);i<2;i++){[w->sunMiddleMaps[i] release];w->sunMiddleMaps[i]=nil;}
        [w->sunCached[1] release];w->sunCached[1]=nil;w->sunValid[0]=w->sunValid[1]=0;
        w->sunMap=w->sunBaseMap;w->sunSecondMap=w->sunFarMode&&w->sunFarDirect?w->sunFarCache:w->sunCached[0];w->sunBlending=0;
        memcpy(w->sunBlendFrame+64,far,64);
        float count=cascaded;memcpy(w->sunBlendFrame+140,&count,4);
        memcpy(w->sunBlendFrame+144,far+112,16);
    } else if(blend) {
        float amount=*(const float *)(bytes+432);
        if(!isfinite(amount)||amount<0||amount>1) { snprintf(err,cap,"Invalid shadow blend");return -1; }
        [w->sunBaseMap release];w->sunBaseMap=nil;
        for(int i=0;i<2;i++){[w->sunMiddleMaps[i] release];w->sunMiddleMaps[i]=nil;}
        OwnSunKey keys[2];memset(keys,0,sizeof keys);
        for(int i=0;i<2;i++) {
            const uint8_t *sample=bytes+144+i*144;const uint32_t *sv=(const uint32_t *)sample;
            if(sv[34]!=requested||!sv[35]) { snprintf(err,cap,"Mismatched shadow samples");return -1; }
            memcpy(&keys[i].revision,bytes+440,8);keys[i].atlas=(uintptr_t)atlas;keys[i].size=requested;keys[i].sections=sv[32];
            memcpy(keys[i].anchor,bytes+448,12);memcpy(keys[i].direction,sample+96,12);
        }
        int slots[2]={-1,-1};
        for(int i=0;i<2;i++)for(int j=0;j<2;j++)if(w->sunValid[j]&&!memcmp(&keys[i],&w->sunKeys[j],sizeof(OwnSunKey)))slots[i]=j;
        if(slots[0]<0)slots[0]=slots[1]==0?1:0;
        if(slots[1]<0)slots[1]=1-slots[0];
        double now=CACurrentMediaTime();
        for(int i=0;i<2;i++) {
            int slot=slots[i];
            BOOL same=w->sunValid[slot]&&!memcmp(&keys[i],&w->sunKeys[slot],sizeof(OwnSunKey));
            if(!w->sunCached[slot]||w->sunCached[slot].width!=requested) {
                id<MTLTexture> next=sunTexture(w,requested,err,cap);if(!next)return -1;
                [w->sunCached[slot] release];w->sunCached[slot]=next;same=NO;
            }
            // Geometry/anchor changes invalidate immediately; a short timeout also refreshes animated alpha textures.
            if(!same||now-w->sunRefreshed[slot]>0.04) {
                if(sunEncode(w,enc,bytes+144+i*144,w->sunCached[slot],arena,sections,recs,atlas,sampler,i,err,cap)<0)return -1;
                w->sunKeys[slot]=keys[i];w->sunValid[slot]=1;w->sunRefreshed[slot]=now;
            } else w->sunCacheHits++;
        }
        w->sunMap=w->sunCached[slots[0]];w->sunSecondMap=w->sunCached[slots[1]];
        memcpy(w->sunBlendFrame,bytes+144,64);memcpy(w->sunBlendFrame+64,bytes+288,64);
        memcpy(w->sunBlendFrame+128,&amount,4);float yes=1;memcpy(w->sunBlendFrame+132,&yes,4);w->sunBlending=1;
    } else {
        sunClearCache(w);w->sunBlending=0;int size=requested?requested:1;
        if(!w->sunBaseMap||w->sunBaseMap.width!=size) {
            id<MTLTexture> next=sunTexture(w,size,err,cap);if(!next)return -1;
            [w->sunBaseMap release];w->sunBaseMap=next;
        }
        if(sunEncode(w,enc,frame,w->sunBaseMap,arena,sections,recs,atlas,sampler,0,err,cap)<0)return -1;
        w->sunMap=w->sunSecondMap=w->sunBaseMap;
    }
    if(!defer && sunGlassUpdate(w,enc,frame,cascaded,err,cap)<0)return -1;
    w->sunGbuffer=defer&&w->sunGbufferEnabled;
    if(defer || ((length==256 || (cascaded && *(const uint32_t *)(bytes+248)))&&requested>0)) {
        if(sunTemporalBegin(w,enc,bytes,err,cap)<0)return -1;
    } else sunTemporalClear(w);
    if(defer) {
        if(w->sunTemporal) {
            w->sunDeferred=1;memcpy(w->sunDeferredPacket,frame,length);
            w->sunDeferredArena=arena;w->sunDeferredSections=sections;w->sunDeferredRecords=recs;
            w->sunDeferredAtlas=atlas;w->sunDeferredSampler=sampler;
            if(!*(const uint32_t *)(bytes+248)) {
                float *config=(float *)(w->sunTemporalFrame+144);config[2]=1;config[3]=0;
            }
        } else {
            w->sunGbuffer=0;
            // Unsupported targets retain forward rendering, without stale receiver data.
            for(int i=0;i<cascaded;i++) {
                const uint8_t *sample=bytes+(i?256+(i-1)*144:0);
                id<MTLTexture> map=i==0?w->sunMap:i==cascaded-1?w->sunSecondMap:w->sunMiddleMaps[i-1];
                if(sunEncodeCascade(w,enc,sample,map,arena,sections,recs,atlas,sampler,i,err,cap)<0)return -1;
            }
            if(sunGlassUpdate(w,enc,frame,cascaded,err,cap)<0)return -1;
        }
    }
    int receiverMode=w->sunDeferred?(!w->sunSurface.current.enabled?3:(w->sunSurface.current.flags&OWN_SUN_NO_RECEIVER_GRID)?2:1):0;
    if(w->sunReceiverMode!=receiverMode) {
        fprintf(stderr,"[siliconshaders sun] receiver culling: %s\n",receiverMode==3?"disabled; native deferred lighting retained":receiverMode==2?"conservative frustum only; full-screen receiver grid skipped":receiverMode==1?"current-frame depth (all cascades)":"forward fallback / disabled");
        w->sunReceiverMode=receiverMode;
    }
    if(w->sunSize!=(int)requested||previousCascades!=cascaded) {
        if(cascaded) {
            fprintf(stderr,"[siliconshaders sun] %d shadow levels:",cascaded);
            for(int i=0;i<cascaded;i++)fprintf(stderr," %u",*(const uint32_t *)(bytes+(i?256+(i-1)*144:0)+136));
            fprintf(stderr," pixels per side\n");
        }
        else fprintf(stderr,"[siliconshaders sun] shadow map %u x %u%s\n",requested,requested,blend?" (smooth)":"");
    }
    w->sunSize=requested;w->sunActive=1;return 1;
    }
}
