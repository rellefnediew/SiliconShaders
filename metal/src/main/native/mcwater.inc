// Current opaque scene inputs for sorted water shading, plus an independent
// after-world underwater pass. Sampled inputs never alias the active color target.
// quality: x reflection search preset, y underwater preset (0 = full quality).
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } OwnWaterFrame;
_Static_assert(sizeof(OwnWaterFrame)==288,"Water frame ABI");
static BOOL waterSrgb(MTLPixelFormat format) { return format==MTLPixelFormatRGBA8Unorm_sRGB||format==MTLPixelFormatBGRA8Unorm_sRGB; }
static BOOL waterFormat(MTLPixelFormat format) {
    return format==MTLPixelFormatRGBA8Unorm||format==MTLPixelFormatBGRA8Unorm||waterSrgb(format)||format==MTLPixelFormatRGBA16Float||format==MTLPixelFormatRG11B10Float;
}
static void waterReleaseInputs(Own *w) {
    [w->waterScene release];[w->waterDepth release];[w->waterPostColor release];[w->waterPostDepth release];
    w->waterScene=w->waterDepth=w->waterPostColor=w->waterPostDepth=nil;w->waterReady=0;
    [w->waterLowMultiply release];[w->waterLowInscatter release];w->waterLowMultiply=w->waterLowInscatter=nil;
}
static BOOL waterTexture(Own *w,id<MTLTexture> *target,id<MTLTexture> source) {
    if(*target&&(*target).width==source.width&&(*target).height==source.height&&(*target).pixelFormat==source.pixelFormat)return YES;
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:source.pixelFormat width:source.width height:source.height mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> next=[w->ctx->device newTextureWithDescriptor:td];if(!next)return NO;
    [*target release];*target=next;return YES;
}
static BOOL waterPostPrepare(Own *w,MTLPixelFormat format,char *err,int cap) {
    if(!waterFormat(format))return NO;
    if(w->waterPostPipeline&&w->waterPostFormat==format)return YES;
    NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=[w->library newFunctionWithName:@"own_water_post_fs"];
    if(!vs||!fs){[vs release];[fs release];snprintf(err,cap,"Water post functions missing");return NO;}
    MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"native underwater lens/fog";
    pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=format;
    id<MTLRenderPipelineState> next=[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];[vs release];[fs release];
    if(!next){snprintf(err,cap,"Water post pipeline: %s",issue.localizedDescription.UTF8String);return NO;}
    [w->waterPostPipeline release];w->waterPostPipeline=next;w->waterPostFormat=format;return YES;
}
// Half-size fog and caustics (quality.w). Any failure here falls back to the full-size pass, not to an error.
static BOOL waterHalfPrepare(Own *w,MTLPixelFormat format,int width,int height) {
    if(!w->waterLowPipeline||!w->waterHalfPipeline||w->waterHalfFormat!=format) {
        NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],low=[w->library newFunctionWithName:@"own_water_low_fs"],half=[w->library newFunctionWithName:@"own_water_post_half_fs"];
        id<MTLRenderPipelineState> nextLow=nil,nextHalf=nil;
        if(vs&&low&&half) {
            MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"underwater fog and caustics, half size";
            pd.vertexFunction=vs;pd.fragmentFunction=low;pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;pd.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA16Float;
            nextLow=[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];
            pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"underwater composite";
            pd.vertexFunction=vs;pd.fragmentFunction=half;pd.colorAttachments[0].pixelFormat=format;
            nextHalf=[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];
        }
        [vs release];[low release];[half release];
        if(!nextLow||!nextHalf){[nextLow release];[nextHalf release];return NO;}
        [w->waterLowPipeline release];[w->waterHalfPipeline release];w->waterLowPipeline=nextLow;w->waterHalfPipeline=nextHalf;w->waterHalfFormat=format;
    }
    int lw=(width+1)/2,lh=(height+1)/2;
    if(!w->waterLowMultiply||(int)w->waterLowMultiply.width!=lw||(int)w->waterLowMultiply.height!=lh) {
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:lw height:lh mipmapped:NO];
        td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
        id<MTLTexture> a=[w->ctx->device newTextureWithDescriptor:td],b=[w->ctx->device newTextureWithDescriptor:td];
        if(!a||!b){[a release];[b release];return NO;}
        a.label=@"underwater multiply";b.label=@"underwater inscatter";
        [w->waterLowMultiply release];[w->waterLowInscatter release];w->waterLowMultiply=a;w->waterLowInscatter=b;
    }
    return YES;
}
int mco_water_frame(Own *w,const void *data,int length) {
    if(!w||!data||length!=sizeof(OwnWaterFrame))return -1;
    const float *values=data;for(int i=0;i<(int)(sizeof(OwnWaterFrame)/4);i++)if(!isfinite(values[i]))return -1;
    memcpy(w->waterFrame,data,sizeof(OwnWaterFrame));w->waterEnabled=values[51]>.5f;w->waterReady=0;
    if(!w->waterEnabled)waterReleaseInputs(w);return 0;
}
int mco_water_begin(Own *w,Enc *enc,char *err,int cap) {
 @autoreleasepool {
    w->waterReady=0;
    if(!w->waterEnabled||!w->sunActive||enc->colorCount!=1||!enc->colors[0]||!enc->depth)return 0;
    id<MTLTexture> color=enc->colors[0],depth=enc->depth;
    if(color.width!=depth.width||color.height!=depth.height||color.textureType!=MTLTextureType2D||depth.textureType!=MTLTextureType2D||!waterFormat(color.pixelFormat)||
        ![w->ctx->device supportsFamily:MTLGPUFamilyApple2])return 0;
    if(!waterPostPrepare(w,color.pixelFormat,err,cap)||!waterTexture(w,&w->waterScene,color)||!waterTexture(w,&w->waterDepth,depth))return 0;
    OwnWaterFrame *frame=(OwnWaterFrame *)w->waterFrame;
    frame->screen.x=color.width;frame->screen.y=color.height;frame->controls.w=waterSrgb(color.pixelFormat);
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"water opaque color/depth snapshot";
    [b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(color.width,color.height,1)
        toTexture:w->waterScene destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
    [b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(depth.width,depth.height,1)
        toTexture:w->waterDepth destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[b endEncoding];[b release];
    id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,depth,0,0,color.width,color.height);
    w->waterReady=1;w->lastPso=nil;
    // Optional programmable blending is tested before drawing water. Failure
    // keeps the ordinary transparent pipeline and its original texture/light.
    if(!ownPipeline(w,enc,3,err,cap)){w->waterReady=0;err[0]=0;return 0;}
    w->waterSnapshots++;return 1;
 }
}
int mco_water_post(Own *w,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,char *err,int cap) {
 @autoreleasepool {
    OwnWaterFrame *frame=(OwnWaterFrame *)w->waterFrame;
    if(!w->waterEnabled||!color||!depth)return 0;
    if(frame->state.y<.5f&&frame->state.z<.002f) {
        [w->waterPostColor release];[w->waterPostDepth release];w->waterPostColor=w->waterPostDepth=nil;
        [w->waterLowMultiply release];[w->waterLowInscatter release];w->waterLowMultiply=w->waterLowInscatter=nil;return 0;
    }
    if(color.textureType!=MTLTextureType2D||depth.textureType!=MTLTextureType2D||color.width!=depth.width||color.height!=depth.height)return 0;
    BOOL copyDepth=!(depth.usage&MTLTextureUsageShaderRead)||depth.storageMode==MTLStorageModeMemoryless;
    if(!waterPostPrepare(w,color.pixelFormat,err,cap)||!waterTexture(w,&w->waterPostColor,color)||(copyDepth&&!waterTexture(w,&w->waterPostDepth,depth)))return -1;
    if(!copyDepth){[w->waterPostDepth release];w->waterPostDepth=nil;}
    BOOL resume=enc->render!=nil;id<MTLTexture> originalDepth=enc->depth;
    frame->screen.x=color.width;frame->screen.y=color.height;frame->controls.w=waterSrgb(color.pixelFormat);
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"underwater scene/depth snapshot";
    [b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(color.width,color.height,1)
        toTexture:w->waterPostColor destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
    // This post pass has no depth attachment: a shader-readable depth input can stay in place.
    if(copyDepth) {
        [b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(depth.width,depth.height,1)
            toTexture:w->waterPostDepth destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
    }
    [b endEncoding];[b release];
    // Fully submerged only: at the surface, the same pass has no fog or caustics to spare.
    BOOL half=frame->quality.w>.5f&&frame->state.y>.5f&&frame->state.w>.5f&&waterHalfPrepare(w,color.pixelFormat,(int)color.width,(int)color.height);
    if(half) {
        MTLRenderPassDescriptor *lowPass=[MTLRenderPassDescriptor renderPassDescriptor];
        lowPass.colorAttachments[0].texture=w->waterLowMultiply;lowPass.colorAttachments[0].loadAction=MTLLoadActionDontCare;lowPass.colorAttachments[0].storeAction=MTLStoreActionStore;
        lowPass.colorAttachments[1].texture=w->waterLowInscatter;lowPass.colorAttachments[1].loadAction=MTLLoadActionDontCare;lowPass.colorAttachments[1].storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> l=mc_profiled_render(enc,command,lowPass);l.label=@"underwater fog and caustics, half size";
        [l setRenderPipelineState:w->waterLowPipeline];[l setCullMode:MTLCullModeNone];[l setViewport:(MTLViewport){0,0,w->waterLowMultiply.width,w->waterLowMultiply.height,0,1}];
        [l setFragmentBytes:frame length:sizeof *frame atIndex:0];[l setFragmentTexture:copyDepth?w->waterPostDepth:depth atIndex:1];
        [l drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[l endEncoding];[l release];
    }
    MTLRenderPassDescriptor *pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=color;
    pd.colorAttachments[0].loadAction=MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,pd);r.label=half?@"underwater lens and composite":@"underwater distance fog, caustics and lens";
    [r setRenderPipelineState:half?w->waterHalfPipeline:w->waterPostPipeline];[r setCullMode:MTLCullModeNone];[r setViewport:(MTLViewport){0,0,color.width,color.height,0,1}];
    [r setFragmentBytes:frame length:sizeof *frame atIndex:0];[r setFragmentTexture:w->waterPostColor atIndex:0];[r setFragmentTexture:copyDepth?w->waterPostDepth:depth atIndex:1];
    if(half){[r setFragmentTexture:w->waterLowMultiply atIndex:2];[r setFragmentTexture:w->waterLowInscatter atIndex:3];}
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];w->waterPosts++;
    if(resume){id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,originalDepth,0,0,color.width,color.height);}
    w->lastPso=nil;return 1;
 }
}
int mco_water_debug_ready(Own *w) { return w->waterReady; }
void mco_water_debug_stats(Own *w,uint64_t *out) { out[0]=w->waterSnapshots;out[1]=w->waterPosts; }

id<MTLTexture> mco_water_debug_scene(Own *w) { return w->waterScene; }
id<MTLTexture> mco_water_debug_depth(Own *w) { return w->waterDepth; }
