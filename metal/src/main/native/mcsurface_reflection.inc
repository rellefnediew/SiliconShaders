// Opaque world reflections before classic transparency. Half-size tracing by default; direct readable scene inputs and
// Apple framebuffer fetch avoid a full-color copy. All buffers are skipped/released with the feature disabled.
typedef struct {
    id<MTLRenderPipelineState> trace,composite;
    MTLPixelFormat format;
    id<MTLTexture> result,colorCopy,depthCopy;
    uint64_t traces,copies;
} OwnSurfaceReflection;
static void surfaceReflectionInputsRelease(OwnSurfaceReflection *r){
    if(!r)return;[r->result release];[r->colorCopy release];[r->depthCopy release];r->result=r->colorCopy=r->depthCopy=nil;
}
static void surfaceReflectionInputsClear(Own *w){if(w)surfaceReflectionInputsRelease(w->surfaceReflection);}
static id<MTLRenderPipelineState> surfaceReflectionPipeline(Own *w,const char *name,MTLPixelFormat format,char *err,int cap){
    NSError *issue=nil;MTLFunctionConstantValues *values=[[[MTLFunctionConstantValues alloc] init] autorelease];bool on=true;
    [values setConstantValue:&on type:MTLDataTypeBool atIndex:51];
    id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=[w->library newFunctionWithName:@(name) constantValues:values error:&issue];
    MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=format;
    id<MTLRenderPipelineState> p=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue]:nil;[vs release];[fs release];
    if(!p)snprintf(err,cap,"Material reflections %s: %s",name,issue.localizedDescription.UTF8String);return p;
}
void mco_surface_reflection_rain_radius(Own *w,float radius){if(w&&isfinite(radius)){w->surfacePuddleRadius=fminf(128,fmaxf(0,radius));w->surfacePuddleRadiusSet=1;}}
void mco_surface_reflection_fog_culling(Own *w,int enabled){if(w)w->surfaceReflectionFogCullDisabled=!enabled;}
// 0 is unknown (legacy/native callers), 1 no possible receiver, 2 possible: uncertainty always keeps the pass.
void mco_surface_reflection_receivers(Own *w,int state){if(!w)return;w->surfaceReflectionReceivers=state==1?1:state==2?2:0;if(state==1)surfaceReflectionInputsClear(w);}
int mco_surface_reflections(Own *w,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,char *err,int cap){
 @autoreleasepool {
    if(!w||!enc)return -1;OwnSurfaceReflection *r=w->surfaceReflection;
    if(w->surfaceReflectionReceivers==1||!w->surfaceMaterialsOn||w->surfaceReflect[0]<.5f||w->surfaceReflect[1]<=0||!w->sunActive||!w->sunLastLighting||w->sunGbufferView!=0){surfaceReflectionInputsRelease(r);return 0;}
    if(!color||!depth||!w->sunRaw||!w->sunLightData||!w->sunAlbedo||!w->sunHistory[w->sunHistoryIndex]||color.width!=depth.width||color.height!=depth.height||
       color.width!=w->sunRaw.width||color.height!=w->sunRaw.height||color.sampleCount!=1||depth.pixelFormat!=MTLPixelFormatDepth32Float||
       !waterFormat(color.pixelFormat)||![w->ctx->device supportsFamily:MTLGPUFamilyApple2])return 0;
    if(!r){r=calloc(1,sizeof *r);if(!r)return -1;w->surfaceReflection=r;}
    if(!r->trace)r->trace=surfaceReflectionPipeline(w,"surface_reflection_fs",MTLPixelFormatRGBA16Float,err,cap);
    if(!r->composite||r->format!=color.pixelFormat){
        id<MTLRenderPipelineState> next=surfaceReflectionPipeline(w,"surface_reflection_composite_fs",color.pixelFormat,err,cap);
        if(!next)return -1;[r->composite release];r->composite=next;r->format=color.pixelFormat;
    }
    if(!r->trace)return -1;
    int quality=(int)fminf(2,fmaxf(0,roundf(w->surfaceReflect[2]))),resolution=(int)fminf(2,fmaxf(0,roundf(w->surfaceReflect[5])));NSUInteger divisor=1u<<resolution;
    NSUInteger width=(color.width+divisor-1)/divisor,height=(color.height+divisor-1)/divisor;
    if(!r->result||r->result.width!=width||r->result.height!=height){
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:width height:height mipmapped:NO];td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;td.storageMode=MTLStorageModePrivate;
        id<MTLTexture> next=[w->ctx->device newTextureWithDescriptor:td];if(!next){snprintf(err,cap,"Cannot allocate material reflection target");return -1;}[r->result release];r->result=next;
    }
    BOOL copyColor=!(color.usage&MTLTextureUsageShaderRead),copyDepth=!(depth.usage&MTLTextureUsageShaderRead);
    if((copyColor&&!waterTexture(w,&r->colorCopy,color))||(copyDepth&&!waterTexture(w,&r->depthCopy,depth))){snprintf(err,cap,"Cannot allocate material reflection fallback inputs");return -1;}
    if(!copyColor){[r->colorCopy release];r->colorCopy=nil;}if(!copyDepth){[r->depthCopy release];r->depthCopy=nil;}
    OwnWaterFrame frame={.view=w->sunSurface.current.clip,.inverse=simd_inverse(w->sunSurface.current.clip)};
    const float *matrix=(const float *)&frame.view;for(int i=0;i<16;i++)if(!isfinite(matrix[i]))return 0;
    frame.screen=(simd_float4){color.width,color.height,width,height};frame.state.x=fminf(2,fmaxf(0,w->surfaceReflect[1]));frame.state.y=!w->surfaceReflectionFogCullDisabled;
    float radius=w->surfacePuddleRadiusSet?w->surfacePuddleRadius:32;
    frame.state.z=radius;frame.state.w=radius*radius;
    simd_float4 eye4=frame.inverse.columns[2];simd_float3 eye=fabsf(eye4.w)>1e-8?eye4.xyz/eye4.w:(simd_float3){0};
    frame.controls.x=radius+simd_length(eye); // conservative early cull using camera-relative G-buffer distance
    frame.controls.y=fminf(48,fmaxf(8,w->surfaceReflect[4]));frame.controls.w=waterSrgb(color.pixelFormat);frame.quality.x=quality;frame.effects.w=fminf(128,fmaxf(16,w->surfaceReflect[3]));frame.camera.w=.08;
    OwnSunLighting lighting={0};sunLightingFill(w,&lighting);
    BOOL resume=enc->render!=nil;id<MTLTexture> originalDepth=enc->depth;
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);
    if(copyColor||copyDepth){id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"material reflection input fallback";
        if(copyColor){[b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(color.width,color.height,1) toTexture:r->colorCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];r->copies++;}
        if(copyDepth){[b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(depth.width,depth.height,1) toTexture:r->depthCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];r->copies++;}[b endEncoding];[b release];
    }
    for(int pass=0;pass<2;pass++){
        id<MTLTexture> target=pass?color:r->result;MTLRenderPassDescriptor *pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=target;pd.colorAttachments[0].loadAction=pass?MTLLoadActionLoad:MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> draw=mc_profiled_render(enc,command,pd);draw.label=pass?@"material reflections edge-aware composition":@"material reflections screen trace";
        [draw setRenderPipelineState:pass?r->composite:r->trace];[draw setCullMode:MTLCullModeNone];[draw setViewport:(MTLViewport){0,0,target.width,target.height,0,1}];
        [draw setFragmentBytes:&frame length:sizeof frame atIndex:0];[draw setFragmentBytes:&lighting length:sizeof lighting atIndex:1];
        // Both passes: the trace culls fully fogged receivers and places rain puddles in world space, the composition applies fog.
        {
            if(w->sunFog)[draw setFragmentBuffer:w->sunFog offset:w->sunFogOffset atIndex:2];
            else {float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};[draw setFragmentBytes:fog length:sizeof fog atIndex:2];}
            [draw setFragmentBytes:w->sunFrame length:144 atIndex:3];
        }
        [draw setFragmentTexture:pass?r->result:copyColor?r->colorCopy:color atIndex:0];[draw setFragmentTexture:copyDepth?r->depthCopy:depth atIndex:1];
        [draw setFragmentTexture:w->sunAlbedo atIndex:2];[draw setFragmentTexture:w->sunLightData atIndex:3];[draw setFragmentTexture:w->sunRaw atIndex:4];[draw setFragmentTexture:w->sunHistory[w->sunHistoryIndex] atIndex:5];
        [draw drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[draw endEncoding];[draw release];
    }
    if(resume){id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,originalDepth,0,0,color.width,color.height);}
    r->traces++;w->lastPso=nil;return 1;
 }
}
void mco_surface_reflection_stats(Own *w,uint64_t *out){OwnSurfaceReflection *r=w?w->surfaceReflection:NULL;out[0]=r?r->traces:0;out[1]=r?r->copies:0;out[2]=r&&r->result?r->result.width*r->result.height*8:0;}
void mco_surface_reflection_reset(Own *w){if(!w||!w->surfaceReflection)return;OwnSurfaceReflection *r=w->surfaceReflection;surfaceReflectionInputsRelease(r);[r->trace release];[r->composite release];free(r);w->surfaceReflection=NULL;w->surfaceReflectionReceivers=0;}
