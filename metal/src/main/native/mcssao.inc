// Apple Silicon SSAO on the current command buffer. Two passes; no color copy, readback, extra queue or wait.
typedef struct {simd_float4x4 inverse;simd_float4 screen,controls,extra;} OwnSsaoFrame;
_Static_assert(sizeof(OwnSsaoFrame)==112,"SSAO packet ABI");
typedef struct {NSMutableDictionary *pipelines;id<MTLTexture> mask,depthCopy;uint64_t passes,depthCopies;} OwnSsao;
void mco_ssao_reset(Own *w){
    if(!w||!w->ssaoPost)return;OwnSsao *s=w->ssaoPost;
    [s->mask release];[s->depthCopy release];[s->pipelines release];free(s);w->ssaoPost=NULL;
}
void mco_ssao_debug_stats(Own *w,uint64_t *stats){
    OwnSsao *s=w?w->ssaoPost:NULL;stats[0]=s?s->passes:0;stats[1]=s?s->depthCopies:0;
    stats[2]=s&&s->mask?s->mask.width*s->mask.height*8:0;stats[3]=s&&s->depthCopy?1:0;
}
static id<MTLRenderPipelineState> ssaoPipeline(Own *w,OwnSsao *s,BOOL apply,MTLPixelFormat format,uint32_t quality,char *err,int cap){
    NSNumber *key=@(((NSUInteger)format<<3)|((apply?0:quality)<<1)|(apply?1:0)); // a small number allocates nothing
    id<MTLRenderPipelineState> cached=s->pipelines[key];if(cached)return cached;
    NSError *issue=nil;MTLFunctionConstantValues *cv=[[[MTLFunctionConstantValues alloc] init] autorelease];
    [cv setConstantValue:&quality type:MTLDataTypeUInt atIndex:50];
    id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=[w->library newFunctionWithName:apply?@"ssao_apply_fs":@"ssao_mask_fs" constantValues:cv error:&issue];
    MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=format;
    id<MTLRenderPipelineState> next=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue]:nil;
    [vs release];[fs release];if(!next){ownErr(issue,"SSAO pipeline",err,cap);return nil;}
    s->pipelines[key]=next;[next release];return s->pipelines[key];
}
int mco_ssao_post(Own *w,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,const void *packet,int bytes,char *err,int cap){
 @autoreleasepool {
    if(!w||!enc||!packet||bytes!=sizeof(OwnSsaoFrame)){snprintf(err,cap,"Invalid SSAO packet");return -1;}
    OwnSsaoFrame f;memcpy(&f,packet,sizeof f);
    for(int i=0;i<28;i++)if(!isfinite(((float *)&f)[i])){snprintf(err,cap,"Nonfinite SSAO packet");return -1;}
    if(f.controls.y<=0){mco_ssao_reset(w);return 0;}
    if(!color||!depth||enc->dead||color.textureType!=MTLTextureType2D||depth.textureType!=MTLTextureType2D||color.sampleCount!=1||depth.sampleCount!=1
       ||color.storageMode==MTLStorageModeMemoryless||depth.storageMode==MTLStorageModeMemoryless||!waterFormat(color.pixelFormat)
       ||depth.pixelFormat!=MTLPixelFormatDepth32Float||depth.width!=color.width||depth.height!=color.height
       ||![w->ctx->device supportsFamily:MTLGPUFamilyApple2])return 0;
    f.screen=(simd_float4){color.width,color.height,0,waterSrgb(color.pixelFormat)};
    f.controls.x=fminf(3,fmaxf(.25,f.controls.x));f.controls.y=fminf(1.5,f.controls.y);f.controls.w=fminf(96,fmaxf(8,f.controls.w));
    uint32_t quality=(uint32_t)fminf(2,fmaxf(0,roundf(f.controls.z)));
    if(!w->ssaoPost){OwnSsao *next=calloc(1,sizeof *next);if(!next){snprintf(err,cap,"Cannot allocate SSAO state");return -1;}next->pipelines=[NSMutableDictionary new];w->ssaoPost=next;}
    OwnSsao *s=w->ssaoPost;
    id<MTLRenderPipelineState> mask=ssaoPipeline(w,s,NO,MTLPixelFormatRGBA16Float,quality,err,cap),apply=ssaoPipeline(w,s,YES,color.pixelFormat,quality,err,cap);
    if(!mask||!apply)return -1;
    if(!cameraImage(w,&s->mask,(color.width+1)/2,(color.height+1)/2,MTLPixelFormatRGBA16Float)){snprintf(err,cap,"Cannot allocate SSAO mask");return -1;}
    BOOL copy=!(depth.usage&MTLTextureUsageShaderRead);
    if(copy&&!cameraImage(w,&s->depthCopy,depth.width,depth.height,depth.pixelFormat)){snprintf(err,cap,"Cannot allocate SSAO depth fallback");return -1;}
    if(!copy){[s->depthCopy release];s->depthCopy=nil;}
    OwnSunLighting lighting={0};sunLightingFill(w,&lighting);lighting.enabled=w->sunActive&&w->sunLastLighting;lighting.srgb=waterSrgb(color.pixelFormat);
    BOOL resume=enc->render!=nil;id<MTLTexture> originalDepth=enc->depth;
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);
    if(copy){id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"SSAO depth fallback copy";
        [b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(depth.width,depth.height,1)
            toTexture:s->depthCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[b endEncoding];[b release];s->depthCopies++;}
    for(int pass=0;pass<2;pass++){
        id<MTLTexture> target=pass?color:s->mask;
        MTLRenderPassDescriptor *pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=target;
        pd.colorAttachments[0].loadAction=pass?MTLLoadActionLoad:MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,pd);if(!r){snprintf(err,cap,"Cannot begin SSAO pass");return -1;}
        r.label=pass?@"SSAO: edge-aware upsample and Apple color fetch":@"SSAO: half-resolution contacts";
        [r setRenderPipelineState:pass?apply:mask];[r setCullMode:MTLCullModeNone];[r setViewport:(MTLViewport){0,0,target.width,target.height,0,1}];
        [r setFragmentBytes:&f length:sizeof f atIndex:0];[r setFragmentTexture:copy?s->depthCopy:depth atIndex:0];
        if(pass){
            [r setFragmentTexture:s->mask atIndex:1];[r setFragmentBytes:&lighting length:sizeof lighting atIndex:1];
            if(w->sunFog)[r setFragmentBuffer:w->sunFog offset:w->sunFogOffset atIndex:2];
            else {float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};[r setFragmentBytes:fog length:sizeof fog atIndex:2];}
            [r setFragmentBytes:w->sunFrame length:144 atIndex:3];
        }
        [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];s->passes++;
    }
    if(resume){id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,originalDepth,0,0,color.width,color.height);}
    return 1;
 }
}
