// Optional camera effects. All work stays on the existing frame command buffer.
// grain: on, strength, size in pixels at 1080 lines. Packets from before film grain end at 432 bytes and leave it off.
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor;simd_float4 p[16];simd_float4 grain;} OwnCameraFrame;
_Static_assert(sizeof(OwnCameraFrame)==448,"Camera packet ABI");
#define CAMERA_LEGACY_PACKET 432
typedef struct {simd_float4 info,screen[8],direction[8];} OwnCameraEmitter;
typedef struct {
    NSMutableDictionary *pipelines;BOOL hdr,hdrFallbackWarned;OwnSunLighting tone;
    id<MTLTexture> colorCopy,depthCopy,blur,bloom[5],probe[2],dofHalf[2],dofTile[2],dof,rays,grain;
    int probeIndex,probeValid,lastMask,lastFetch;
    uint64_t passes,colorCopies,depthCopies;
    simd_float4 raysColor;
    OwnCameraEmitter emitter;float emitterState[56];int emitterWasCustom;
    // The pipelines in use, for the lookups every frame; the dictionary owns them.
    struct {const char *function;MTLPixelFormat format;int mask;uint32_t rayLevels;BOOL additive,optimized,hdr;id<MTLRenderPipelineState> pipeline;} recent[32];int recentCount;
} OwnCameraPost;
static void cameraEmission(Own *w,OwnCameraPost *c,OwnCameraFrame *f) {
    memset(&c->emitter,0,sizeof c->emitter);
    simd_float3 light;memcpy(&light,w->sunFrame+96,12);if(w->sunMoonLight)light=-light;
    BOOL custom=w->sunCelestial[14]>.5f&&w->sunEnvSet&&(w->sunEnv[3]>.5f||w->sunEnv[2]>.5f)&&sunTuningValues(w)[67]>.5f&&w->sunGbufferEnabled&&w->sunGbufferView==0&&light.y<0;
    if(custom!=c->emitterWasCustom||(custom&&memcmp(c->emitterState,w->sunCelestial,sizeof c->emitterState)))c->probeValid=0;
    c->emitterWasCustom=custom;if(!custom)return;
    memcpy(c->emitterState,w->sunCelestial,sizeof c->emitterState);c->emitter.info.x=1;
    simd_float3 moon=-simd_normalize(light),right=simd_normalize(simd_cross((simd_float3){0,0,1},moon)),up=simd_cross(moon,right);
    simd_float4x4 clip=simd_inverse(f->inverse);float radius=tanf(w->sunCelestial[7]);
    float radiance=fmaxf(w->sunCelestial[4],fmaxf(w->sunCelestial[5],w->sunCelestial[6]));
    for(int i=0;i<8;i++) {
        const float *point=w->sunCelestial+24+i*4;
        simd_float3 direction=simd_normalize(moon+radius*(right*point[0]+up*point[1]));float weight=direction.y>0&&radiance>0?point[2]:0;
        c->emitter.direction[i]=(simd_float4){direction.x,direction.y,direction.z,weight};c->emitter.info.y+=weight;
        simd_float4 projected=simd_mul(clip,(simd_float4){direction.x,direction.y,direction.z,0});
        if(projected.w>1e-6&&isfinite(projected.x+projected.y+projected.w)) {
            simd_float2 uv=projected.xy/projected.w*.5f+.5f;
            simd_float3 edge=moon+radius*(right*(point[0]+point[3])+up*point[1]);simd_float4 p=simd_mul(clip,(simd_float4){edge.x,edge.y,edge.z,0});
            float footprint=0;if(p.w>1e-6){simd_float2 delta=(p.xy/p.w*.5f+.5f-uv)*(simd_float2){f->screen.x/f->screen.y,1};footprint=simd_length(delta);}
            c->emitter.screen[i]=(simd_float4){uv.x,uv.y,weight,footprint};
        }
    }
    f->lightColor.x=w->sunCelestial[4]*.5f;f->lightColor.y=w->sunCelestial[5]*.5f;f->lightColor.z=w->sunCelestial[6]*.5f;
}
static float cameraParam(const OwnCameraFrame *f,int i){return f->p[i/4][i%4];}
static void cameraDropImages(OwnCameraPost *c){
    [c->colorCopy release];c->colorCopy=nil;[c->depthCopy release];c->depthCopy=nil;[c->blur release];c->blur=nil;[c->rays release];c->rays=nil;[c->grain release];c->grain=nil;
    for(int i=0;i<5;i++){[c->bloom[i] release];c->bloom[i]=nil;}
    for(int i=0;i<2;i++){[c->probe[i] release];c->probe[i]=nil;[c->dofHalf[i] release];c->dofHalf[i]=nil;[c->dofTile[i] release];c->dofTile[i]=nil;}c->probeValid=0;[c->dof release];c->dof=nil;
}
void mco_camera_reset(Own *w){
    if(!w||!w->cameraPost)return;OwnCameraPost *c=w->cameraPost;cameraDropImages(c);[c->pipelines release];free(c);w->cameraPost=NULL;
}
// Tests append the frozen pre-optimization camera.5 shaders under camera_ref_* names. Normal builds do not contain them.
int mco_camera_debug_rays_reference(Own *w,int enabled){
    if(!w)return -1;
    if(enabled&&![w->library.functionNames containsObject:@"camera_ref_rays_fs"])return -1;
    w->cameraRaysReference=enabled!=0;return 0;
}
static BOOL cameraImage(Own *w,id<MTLTexture> *slot,NSUInteger width,NSUInteger height,MTLPixelFormat format){
    if(*slot&&(*slot).width==width&&(*slot).height==height&&(*slot).pixelFormat==format)return YES;
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
    id<MTLTexture> next=[w->ctx->device newTextureWithDescriptor:td];if(!next)return NO;
    [*slot release];*slot=next;return YES;
}
// Film grain reads one tiling 256x256 white-noise image (64 KB, made once) with a single filtered sample per pixel.
static BOOL cameraGrainNoise(Own *w,OwnCameraPost *c){
    if(c->grain)return YES;
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Unorm width:256 height:256 mipmapped:NO];
    td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;id<MTLTexture> next=[w->ctx->device newTextureWithDescriptor:td];if(!next)return NO;
    uint8_t *noise=malloc(256*256);if(!noise){[next release];return NO;}uint32_t state=0x9E3779B9u;
    for(int i=0;i<256*256;i++){state^=state<<13;state^=state>>17;state^=state<<5;noise[i]=(uint8_t)(state>>24);}
    [next replaceRegion:MTLRegionMake2D(0,0,256,256) mipmapLevel:0 withBytes:noise bytesPerRow:256];free(noise);c->grain=next;return YES;
}
static void cameraPipelineRemember(OwnCameraPost *c,const char *function,MTLPixelFormat format,int mask,uint32_t rayLevels,BOOL additive,BOOL optimized,id<MTLRenderPipelineState> pipeline){
    if(c->recentCount==32)return;__typeof__(c->recent[0]) *r=&c->recent[c->recentCount++];
    r->function=function;r->format=format;r->mask=mask;r->rayLevels=rayLevels;r->additive=additive;r->optimized=optimized;r->hdr=c->hdr;r->pipeline=pipeline;
}
static id<MTLRenderPipelineState> cameraPipelineVariant(Own *w,OwnCameraPost *c,const char *function,MTLPixelFormat format,int mask,BOOL additive,BOOL optimized,char *err,int cap){
    uint32_t rayLevels=!strcmp(function,"camera_rays_fs")?(uint32_t)mask:0;
    if(strstr(function,"bloom")||strstr(function,"dof")||strstr(function,"rays"))mask=0;else if(strstr(function,"probe"))mask&=2|8;else if(strstr(function,"blur"))mask&=4;else mask&=1|8|16|32|64|128|256;
    for(int i=0;i<c->recentCount;i++){__typeof__(c->recent[0]) *r=&c->recent[i];if(r->function==function&&r->format==format&&r->mask==mask&&r->rayLevels==rayLevels&&r->additive==additive&&r->optimized==optimized&&r->hdr==c->hdr)return r->pipeline;}
    NSString *key=[NSString stringWithFormat:@"%s/%lu/%d/%d/%u/%d/%d",function,(unsigned long)format,mask,additive,rayLevels,optimized,c->hdr];id<MTLRenderPipelineState> cached=c->pipelines[key];
    if(cached){cameraPipelineRemember(c,function,format,mask,rayLevels,additive,optimized,cached);return cached;}
    NSError *issue=nil;MTLFunctionConstantValues *constants=[[[MTLFunctionConstantValues alloc] init] autorelease];
    for(int i=0;i<8;i++){bool on=(mask&(1<<i))!=0;[constants setConstantValue:&on type:MTLDataTypeBool atIndex:40+i];}
    bool grain=(mask&256)!=0;[constants setConstantValue:&grain type:MTLDataTypeBool atIndex:53];
    if(rayLevels)[constants setConstantValue:&rayLevels type:MTLDataTypeUInt atIndex:48];
    bool hdr=c->hdr;[constants setConstantValue:&hdr type:MTLDataTypeBool atIndex:54];
    bool optimizedRays=optimized;[constants setConstantValue:&optimizedRays type:MTLDataTypeBool atIndex:49];
    id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_screen_vs"],fs=[w->library newFunctionWithName:[NSString stringWithUTF8String:function] constantValues:constants error:&issue];
    MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.colorAttachments[0].pixelFormat=format;
    if(additive){pd.colorAttachments[0].blendingEnabled=YES;pd.colorAttachments[0].sourceRGBBlendFactor=MTLBlendFactorOne;pd.colorAttachments[0].destinationRGBBlendFactor=MTLBlendFactorOne;pd.colorAttachments[0].sourceAlphaBlendFactor=MTLBlendFactorZero;pd.colorAttachments[0].destinationAlphaBlendFactor=MTLBlendFactorOne;}
    id<MTLRenderPipelineState> next=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue]:nil;[vs release];[fs release];
    if(!next){snprintf(err,cap,"Camera pipeline %s: %s",function,issue.localizedDescription.UTF8String?:"missing shader function");return nil;}
    c->pipelines[key]=next;[next release];cameraPipelineRemember(c,function,format,mask,rayLevels,additive,optimized,c->pipelines[key]);return c->pipelines[key];
}
static id<MTLRenderPipelineState> cameraPipeline(Own *w,OwnCameraPost *c,const char *function,MTLPixelFormat format,int mask,BOOL additive,char *err,int cap){
    return cameraPipelineVariant(w,c,function,format,mask,additive,NO,err,cap);
}
static void cameraDraw(OwnCameraPost *c,Enc *enc,id<MTLCommandBuffer> command,id<MTLTexture> target,id<MTLRenderPipelineState> pipeline,const OwnCameraFrame *frame,simd_float4 pass,id<MTLTexture> source,id<MTLTexture> depth,id<MTLTexture> probe,id<MTLTexture> bloom,id<MTLTexture> rays,BOOL load,NSString *label){
    MTLRenderPassDescriptor *pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=target;pd.colorAttachments[0].loadAction=load?MTLLoadActionLoad:MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,pd);r.label=label;[r setRenderPipelineState:pipeline];[r setCullMode:MTLCullModeNone];
    [r setViewport:(MTLViewport){0,0,target.width,target.height,0,1}];[r setFragmentBytes:frame length:sizeof *frame atIndex:0];[r setFragmentBytes:&pass length:16 atIndex:1];if(rays)[r setFragmentBytes:&c->raysColor length:16 atIndex:2];
    if(c->hdr)[r setFragmentBytes:&c->tone length:sizeof c->tone atIndex:4];
    [r setFragmentBytes:&c->emitter length:sizeof c->emitter atIndex:6];
    [r setFragmentTexture:source atIndex:0];[r setFragmentTexture:depth atIndex:1];[r setFragmentTexture:probe atIndex:2];[r setFragmentTexture:bloom atIndex:3];[r setFragmentTexture:rays atIndex:4];if(c->grain)[r setFragmentTexture:c->grain atIndex:5];
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];c->passes++;
}
static int cameraMask(const OwnCameraFrame *f){
    if(cameraParam(f,0)<.5)return 0;int mask=0;
    if(cameraParam(f,1)>.5&&cameraParam(f,2)>0)mask|=1;
    if(cameraParam(f,8)>.5&&cameraParam(f,12)>0&&(cameraParam(f,13)>0||cameraParam(f,14)>0))mask|=2;
    if(cameraParam(f,17)>.5&&cameraParam(f,18)>0&&cameraParam(f,19)>0&&cameraParam(f,20)>0&&f->light.w>.5&&f->screen.z>0&&f->p[15].w>.5)mask|=4;
    if(cameraParam(f,22)>.5&&cameraParam(f,23)>0)mask|=8;
    if(cameraParam(f,32)>.5&&cameraParam(f,33)>0)mask|=16;
    if(cameraParam(f,36)>.5&&cameraParam(f,37)>0)mask|=32;
    if(cameraParam(f,44)>.5&&cameraParam(f,45)>0&&cameraParam(f,47)>0)mask|=64;
    if(cameraParam(f,48)>.5&&cameraParam(f,49)>0)mask|=128;
    if(f->grain.x>.5&&f->grain.y>0&&f->grain.z>0)mask|=256;
    return mask;
}
typedef struct {simd_float4x4 clips[4];simd_float4 bias,light,info,color;} OwnCameraRays;
_Static_assert(sizeof(OwnCameraRays)==320,"Godrays packet ABI");
static void cameraRaysDraw(Own *w,OwnCameraPost *c,Enc *enc,id<MTLCommandBuffer> command,id<MTLRenderPipelineState> pipeline,const OwnCameraFrame *frame,id<MTLTexture> depth){
    OwnCameraRays rays={0};memcpy(&rays.light,w->sunFrame+96,16);rays.info=(simd_float4){w->sunCascaded,c->rays.width,c->rays.height,cameraParam(frame,59)};
    OwnSunLighting lighting={0};const float *look=w->sunLookSet?w->sunLook:(const float[]){1,1,1};lighting.exposure=look[0];lighting.saturation=look[1];lighting.contrast=look[2];sunLightingFill(w,&lighting);
    // These colors are constant across the image. Keep only scalar scattering + depth in the small target.
    float noon=fminf(1,fmaxf(0,(rays.light.y-.05f)/.4f));noon=noon*noon*(3-2*noon);
    simd_float3 color;
    for(int i=0;i<3;i++)color[i]=lighting.moon>.5f?powf(lighting.p[17][i],2.2f)*lighting.p[17].w:
        (1-noon)*powf(lighting.p[1][i],2.2f)*lighting.p[1].w+noon*powf(lighting.p[0][i],2.2f)*lighting.p[0].w;
    if(c->emitter.info.x>.5f)for(int i=0;i<3;i++)color[i]=w->sunCelestial[4+i]*.5f*lighting.p[17].w;
    if(!c->hdr){color*=lighting.exposure;float luma=simd_dot(color,(simd_float3){.2126f,.7152f,.0722f});color=simd_max((simd_float3){luma,luma,luma}+(color-luma)*lighting.saturation,0);}
    float strength=cameraParam(frame,49)*rays.light.w*(lighting.moon>.5f?cameraParam(frame,55):1);
    if(rays.info.w>.5f){color*=(simd_float3){.22f,.62f,.72f};strength*=cameraParam(frame,56);}
    rays.color=(simd_float4){color.x*strength,color.y*strength,color.z*strength,cameraParam(frame,50)*(1+lighting.env.y*.8f)*(rays.info.w>.5f?2.5f:1)};c->raysColor=rays.color;
    MTLRenderPassDescriptor *pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=c->rays;pd.colorAttachments[0].loadAction=MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=mc_profiled_render(enc,command,pd);r.label=@"camera volumetric godrays (reused shadow maps)";[r setRenderPipelineState:pipeline];[r setCullMode:MTLCullModeNone];[r setViewport:(MTLViewport){0,0,c->rays.width,c->rays.height,0,1}];
    for(int i=0;i<4;i++){
        const uint8_t *clip=i==0?w->sunFrame:i==w->sunCascaded-1?w->sunBlendFrame+64:w->sunBlendFrame+160+(i-1)*64;
        if(i<w->sunCascaded){memcpy(&rays.clips[i],clip,64);rays.bias[i]=.00015f;}
        id<MTLTexture> map=i>=w->sunCascaded?w->sunMap:i==0?w->sunMap:i==w->sunCascaded-1?w->sunSecondMap:w->sunMiddleMaps[i-1];
        [r setFragmentTexture:map?:w->sunMap atIndex:20+i];
    }
    [r setFragmentBytes:frame length:sizeof *frame atIndex:0];[r setFragmentBytes:&rays length:sizeof rays atIndex:1];if(w->cameraRaysReference||cameraParam(frame,57)<.5f)[r setFragmentBytes:&lighting length:sizeof lighting atIndex:2];[r setFragmentTexture:depth atIndex:1];
    [r setFragmentBytes:&c->emitter length:sizeof c->emitter atIndex:6];
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[r release];c->passes++;
}
static int cameraPostImpl(Own *w,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,id<MTLTexture> output,BOOL hdr,const void *data,int length,char *err,int cap){
 @autoreleasepool {
    if(!w||!enc||!data||(length!=sizeof(OwnCameraFrame)&&length!=CAMERA_LEGACY_PACKET))return -1;
    const float *values=data;for(int i=0;i<length/4;i++)if(!isfinite(values[i])){snprintf(err,cap,"Nonfinite camera packet");return -1;}
    OwnCameraFrame f;memset(&f,0,sizeof f);memcpy(&f,data,length);int mask=cameraMask(&f);
    if((mask&128)&&(!w->sunActive||w->sunCascaded<2||w->sunCascaded>4||!w->sunMap||!w->sunSampler||*(const float *)(w->sunFrame+108)<=0||
        cameraParam(&f,59)<0||(w->sunMoonLight&&cameraParam(&f,55)<=0)||(cameraParam(&f,59)>.5&&cameraParam(&f,56)<=0)))mask&=~128;
    if(mask&128){float range=*(const float *)(w->sunBlendFrame+144);f.p[12].w=fminf(f.p[12].w,fmaxf(1,range));if(cameraParam(&f,59)>.5)f.p[12].w=fminf(f.p[12].w,32);}
    if(!mask&&!hdr){
        // Motion blur alone pauses while the camera stands still: keep its images for the next movement.
        if(cameraParam(&f,0)>.5&&cameraParam(&f,17)>.5&&cameraParam(&f,18)>0&&cameraParam(&f,19)>0&&cameraParam(&f,20)>0){if(w->cameraPost)((OwnCameraPost *)w->cameraPost)->lastMask=0;}
        else mco_camera_reset(w);return 0;
    }
    if(!color||color.textureType!=MTLTextureType2D||color.sampleCount!=1||color.storageMode==MTLStorageModeMemoryless||!waterFormat(color.pixelFormat))return 0;
    if(!output||output.width!=color.width||output.height!=color.height||(output==color&&hdr)||!waterFormat(output.pixelFormat)||output.sampleCount!=1)return 0;
    BOOL needsDepth=(mask&(2|4|8|128))!=0;
    if(needsDepth&&(!depth||depth.width!=color.width||depth.height!=color.height||depth.pixelFormat!=MTLPixelFormatDepth32Float||depth.sampleCount!=1||depth.storageMode==MTLStorageModeMemoryless))return 0;
    if(!w->cameraPost){OwnCameraPost *next=calloc(1,sizeof *next);if(!next)return -1;next->pipelines=[NSMutableDictionary new];w->cameraPost=next;}
    OwnCameraPost *c=w->cameraPost;c->lastMask=mask;c->hdr=hdr;f.screen.x=color.width;f.screen.y=color.height;
    cameraEmission(w,c,&f);
    if(c->emitter.info.x>.5f&&c->emitter.info.y<=0){mask&=~(8|128);c->lastMask=mask;c->probeValid=0;if(!mask&&!hdr){cameraDropImages(c);return 0;}}
    if(hdr){
        memset(&c->tone,0,sizeof c->tone);sunLightingFill(w,&c->tone);c->tone.materials[3].w=0;
        const float *look=w->sunLookSet?w->sunLook:(const float[]){1,1,1};c->tone.exposure=look[0];c->tone.saturation=look[1];c->tone.contrast=look[2];
    }
    f.screen.x=color.width;f.screen.y=color.height;f.screen.z=fminf(.25,fmaxf(0,f.screen.z));f.screen.w=waterSrgb(color.pixelFormat);
    BOOL blur=(mask&(2|4))!=0,fetch=!hdr&&!(mask&(2|4|16|64))&&[w->ctx->device supportsFamily:MTLGPUFamilyApple2];
    BOOL optimized=(mask&128)&&cameraParam(&f,57)>.5f&&!w->cameraRaysReference;
    id<MTLRenderPipelineState> composite=cameraPipelineVariant(w,c,w->cameraRaysReference?(fetch?"camera_ref_composite_fetch_fs":"camera_ref_composite_fs"):(fetch?"camera_composite_fetch_fs":"camera_composite_fs"),output.pixelFormat,mask,NO,optimized,err,cap);
    if(!composite&&fetch){fetch=NO;composite=cameraPipelineVariant(w,c,w->cameraRaysReference?"camera_ref_composite_fs":"camera_composite_fs",output.pixelFormat,mask,NO,optimized,err,cap);}
    if(!composite)return -1;c->lastFetch=fetch;
    if(mask&256){if(!cameraGrainNoise(w,c))return -1;}else{[c->grain release];c->grain=nil;}
    BOOL probe=(mask&(2|8))!=0; // one GPU texel, even with manual focus, never a CPU depth read
    id<MTLRenderPipelineState> blurPipeline=nil,probePipeline=nil,down=nil,up=nil;
    BOOL dof=(mask&2)!=0,motion=(mask&4)!=0;id<MTLRenderPipelineState> dofPrefilter=nil,dofTiles=nil,dofDilate=nil,dofGather=nil,dofComposite=nil;
    if(dof){
        NSUInteger halfWidth=MAX(1,(color.width+1)/2),halfHeight=MAX(1,(color.height+1)/2);
        dofPrefilter=cameraPipeline(w,c,"camera_dof_prefilter_fs",MTLPixelFormatRGBA16Float,mask,NO,err,cap);dofGather=cameraPipeline(w,c,"camera_dof_gather_fs",MTLPixelFormatRGBA16Float,mask,NO,err,cap);
        dofTiles=cameraPipeline(w,c,"camera_dof_tiles_fs",MTLPixelFormatR16Float,mask,NO,err,cap);dofDilate=cameraPipeline(w,c,"camera_dof_dilate_fs",MTLPixelFormatR16Float,mask,NO,err,cap);
        dofComposite=cameraPipeline(w,c,"camera_dof_composite_fs",color.pixelFormat,mask,NO,err,cap);if(!dofPrefilter||!dofTiles||!dofDilate||!dofGather||!dofComposite)return -1;
        for(int i=0;i<2;i++)if(!cameraImage(w,&c->dofHalf[i],halfWidth,halfHeight,MTLPixelFormatRGBA16Float)||!cameraImage(w,&c->dofTile[i],(halfWidth+15)/16,(halfHeight+15)/16,MTLPixelFormatR16Float))return -1;
        if(!cameraImage(w,&c->dof,color.width,color.height,color.pixelFormat))return -1;
    }
    if(motion){blurPipeline=cameraPipeline(w,c,"camera_blur_fs",color.pixelFormat,mask,NO,err,cap);if(!blurPipeline||!cameraImage(w,&c->blur,color.width,color.height,color.pixelFormat))return -1;}
    if(probe){NSUInteger width=c->emitter.info.x>.5f&&(mask&8)?9:1;MTLPixelFormat format=MTLPixelFormatRGBA32Float;
        if(c->probe[0]&&c->probe[0].width!=width)c->probeValid=0;
        probePipeline=cameraPipeline(w,c,"camera_probe_fs",format,mask,NO,err,cap);if(!probePipeline)return -1;
        for(int i=0;i<2;i++)if(!cameraImage(w,&c->probe[i],width,1,format))return -1;}
    id<MTLRenderPipelineState> raysPipeline=nil;
    if(mask&128){
        float scale=fminf(.5f,fmaxf(.25f,cameraParam(&f,53)));
        MTLPixelFormat rayFormat=optimized?MTLPixelFormatRG16Float:MTLPixelFormatRGBA16Float;
        raysPipeline=cameraPipeline(w,c,w->cameraRaysReference?"camera_ref_rays_fs":optimized?"camera_rays_fs":"camera_rays_original_fs",rayFormat,optimized?w->sunCascaded:0,NO,err,cap);
        if(!raysPipeline||!cameraImage(w,&c->rays,MAX(1,(NSUInteger)ceil(color.width*scale)),MAX(1,(NSUInteger)ceil(color.height*scale)),rayFormat))return -1;
    }else{[c->rays release];c->rays=nil;}
    BOOL readable=(color.usage&MTLTextureUsageShaderRead)!=0;
    BOOL sourceNeeded=blur||(mask&1)||!fetch,colorCopy=sourceNeeded&&(!readable||(!blur&&!fetch&&output==color));
    BOOL depthCopy=needsDepth&&!(depth.usage&MTLTextureUsageShaderRead);
    if(colorCopy&&!cameraImage(w,&c->colorCopy,color.width,color.height,color.pixelFormat))return -1;
    if(depthCopy&&!cameraImage(w,&c->depthCopy,depth.width,depth.height,depth.pixelFormat))return -1;
    int levels=0;
    if(mask&1){
        down=cameraPipeline(w,c,"camera_bloom_down_fs",MTLPixelFormatRGBA16Float,mask,NO,err,cap);up=cameraPipeline(w,c,"camera_bloom_up_fs",MTLPixelFormatRGBA16Float,mask,YES,err,cap);if(!down||!up)return -1;
        int wanted=MAX(1,MIN(5,(int)cameraParam(&f,6)));float scale=fminf(.5f,fmaxf(.25f,cameraParam(&f,7)));NSUInteger width=MAX(1,(NSUInteger)ceil(color.width*scale)),height=MAX(1,(NSUInteger)ceil(color.height*scale));
        for(int i=0;i<wanted;i++){if(!cameraImage(w,&c->bloom[i],width,height,MTLPixelFormatRGBA16Float))return -1;levels++;if(width==1&&height==1)break;width=MAX(1,(width+1)/2);height=MAX(1,(height+1)/2);}
    }
    // Drop resources for disabled effects and obsolete viewport/quality allocations.
    if(!motion){[c->blur release];c->blur=nil;}if(!dof){for(int i=0;i<2;i++){[c->dofHalf[i] release];c->dofHalf[i]=nil;[c->dofTile[i] release];c->dofTile[i]=nil;}[c->dof release];c->dof=nil;}if(!probe){for(int i=0;i<2;i++){[c->probe[i] release];c->probe[i]=nil;}c->probeValid=0;}
    for(int i=levels;i<5;i++){[c->bloom[i] release];c->bloom[i]=nil;}
    if(!colorCopy){[c->colorCopy release];c->colorCopy=nil;}if(!depthCopy){[c->depthCopy release];c->depthCopy=nil;}
    BOOL resume=enc->render!=nil;id<MTLTexture> originalDepth=enc->depth;
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);
    if(colorCopy||depthCopy){
        id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"camera source fallback snapshot";
        if(colorCopy){[b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(color.width,color.height,1) toTexture:c->colorCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];c->colorCopies++;}
        if(depthCopy){[b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(depth.width,depth.height,1) toTexture:c->depthCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];c->depthCopies++;}[b endEncoding];[b release];
    }
    id<MTLTexture> input=colorCopy?c->colorCopy:color,inputDepth=depthCopy?c->depthCopy:depth,probeValue=nil;
    if(mask&128)cameraRaysDraw(w,c,enc,command,raysPipeline,&f,inputDepth);
    if(probe){int next=c->probeIndex^1;OwnCameraFrame pf=f;if(!c->probeValid)pf.light.w=0;cameraDraw(c,enc,command,c->probe[next],probePipeline,&pf,(simd_float4){0},nil,inputDepth,c->probe[c->probeIndex],nil,nil,NO,@"camera autofocus and finite light visibility");c->probeIndex=next;c->probeValid=1;probeValue=c->probe[next];}
    if(dof){
        simd_float4 half={c->dofHalf[0].width,c->dofHalf[0].height,0,0};
        cameraDraw(c,enc,command,c->dofHalf[0],dofPrefilter,&f,half,input,inputDepth,probeValue,nil,nil,NO,@"camera depth of field: color and blur size (half resolution)");
        simd_float4 tiles={c->dofTile[0].width,c->dofTile[0].height,0,0};
        cameraDraw(c,enc,command,c->dofTile[0],dofTiles,&f,tiles,c->dofHalf[0],nil,nil,nil,nil,NO,@"camera depth of field: blur size per block");
        cameraDraw(c,enc,command,c->dofTile[1],dofDilate,&f,tiles,c->dofTile[0],nil,nil,nil,nil,NO,@"camera depth of field: blur reach around blocks");
        cameraDraw(c,enc,command,c->dofHalf[1],dofGather,&f,half,c->dofHalf[0],c->dofTile[1],nil,nil,nil,NO,@"camera depth of field: bokeh gather (half resolution)");
        cameraDraw(c,enc,command,c->dof,dofComposite,&f,(simd_float4){0},input,inputDepth,probeValue,c->dofHalf[1],nil,NO,@"camera depth of field: composite");input=c->dof;
    }
    if(motion){cameraDraw(c,enc,command,c->blur,blurPipeline,&f,(simd_float4){0},input,inputDepth,nil,nil,nil,NO,@"camera motion gather");input=c->blur;}
    if(mask&1){
        id<MTLTexture> source=input;
        for(int i=0;i<levels;i++){cameraDraw(c,enc,command,c->bloom[i],down,&f,(simd_float4){c->bloom[i].width,c->bloom[i].height,cameraParam(&f,5),i==0?1:0},source,nil,nil,nil,nil,NO,@"camera bloom downsample");source=c->bloom[i];}
        for(int i=levels-2;i>=0;i--)cameraDraw(c,enc,command,c->bloom[i],up,&f,(simd_float4){c->bloom[i].width,c->bloom[i].height,cameraParam(&f,5),0},c->bloom[i+1],nil,nil,nil,nil,YES,@"camera bloom upsample");
        f.p[0].z/=levels; // every level adds its full energy: keep strength stable as filter levels change
    }
    cameraDraw(c,enc,command,output,composite,&f,(simd_float4){0,0,0,waterSrgb(output.pixelFormat)},fetch?nil:input,(mask&128)?inputDepth:nil,probeValue,levels?c->bloom[0]:nil,(mask&128)?c->rays:nil,fetch,fetch?@"camera composite (Apple framebuffer fetch)":@"camera composite");
    // Resume the frontend's original attachments; its restored pipeline still targets the world format.
    if(resume){id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,originalDepth,0,0,color.width,color.height);}
    w->lastPso=nil;return 1;
 }
}
int mco_camera_post(Own *w,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,const void *data,int length,char *err,int cap){
    return cameraPostImpl(w,enc,color,depth,color,NO,data,length,err,cap);
}
int mco_camera_hdr_post(Own *w,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,id<MTLTexture> output,const void *data,int length,char *err,int cap){
    if(!color||(color.pixelFormat!=MTLPixelFormatRGBA16Float&&color.pixelFormat!=MTLPixelFormatRG11B10Float))return -1;
    int result=cameraPostImpl(w,enc,color,depth,output,YES,data,length,err,cap);
    if(result>0)return result;
    if(w&&w->cameraPost&&!((OwnCameraPost *)w->cameraPost)->hdrFallbackWarned){fprintf(stderr,"[siliconshaders HDR bloom] Camera effects unavailable (%s); using tone-only resolve\n",err);((OwnCameraPost *)w->cameraPost)->hdrFallbackWarned=YES;}
    OwnCameraFrame plain={0};return cameraPostImpl(w,enc,color,depth,output,YES,&plain,sizeof plain,err,cap);
}
void mco_camera_debug_stats(Own *w,uint64_t *out){
    memset(out,0,6*sizeof *out);if(!w||!w->cameraPost)return;OwnCameraPost *c=w->cameraPost;
    out[0]=c->passes;out[1]=c->colorCopies;out[2]=c->depthCopies;out[3]=c->lastMask;out[4]=c->lastFetch;
    id<MTLTexture> textures[]={c->colorCopy,c->depthCopy,c->blur,c->bloom[0],c->bloom[1],c->bloom[2],c->bloom[3],c->bloom[4],c->probe[0],c->probe[1],c->dofHalf[0],c->dofHalf[1],c->dofTile[0],c->dofTile[1],c->dof,c->rays};
    for(int i=0;i<16;i++)if(textures[i])out[5]+=textures[i].allocatedSize;
}
// Automatic exposure (SunLook): sun_exposure_measure reads 2,048 points of the finished world image into one of three
// small shared buffers. The buffer written three frames ago is read before it is reused, so the CPU never waits on the
// GPU; its frame tag rejects a result the GPU has not written yet. From that image's average, the log2 factor that
// would bring it to middle grey, limited to range stops and scaled by strength, is approached exponentially at speed
// per second. Off (strength 0): no pass, no buffers, factor 1.
int mco_sun_exposure(Own *w,Enc *enc,id<MTLTexture> color,float strength,float speed,float range,float dt){
    if(!w)return 0;
    if(!(strength>0)||!color||!(color.usage&MTLTextureUsageShaderRead)||color.textureType!=MTLTextureType2D||!isfinite(strength+speed+range+dt)){
        w->sunAutoLog=0;w->sunExposureFrame=0;for(int i=0;i<3;i++){[w->sunExposure[i] release];w->sunExposure[i]=nil;}return 0;
    }
    if(!ownLazyKernel(w,&w->sunExposurePipeline,"sun_exposure_measure"))return 0;
    uint32_t frame=(uint32_t)w->sunExposureFrame;int slot=frame%3;
    if(!w->sunExposure[slot]){
        w->sunExposure[slot]=[w->ctx->device newBufferWithLength:16 options:MTLResourceStorageModeShared];if(!w->sunExposure[slot])return 0;
        memset(w->sunExposure[slot].contents,0xff,16);
    }
    const float *r=w->sunExposure[slot].contents;uint32_t tag;memcpy(&tag,r+3,4);
    if(frame>=3&&tag==frame-3&&isfinite(r[0]+r[1]+r[2])){
        float full=fminf(fmaxf(r[1]-r[0]+r[2],-range),range);
        w->sunAutoLog+=(strength*full-w->sunAutoLog)*(1-expf(-fmaxf(dt,0)*speed));
    }
    OwnSunLighting lighting={0};const float *look=w->sunLookSet?w->sunLook:(const float[]){1,1,1};
    lighting.exposure=look[0];lighting.saturation=look[1];lighting.contrast=look[2];sunLightingFill(w,&lighting);
    NSUInteger threads=MIN(1024,w->sunExposurePipeline.maxTotalThreadsPerThreadgroup)&~(NSUInteger)31;if(!threads)return 0;
    BOOL hdr=w->bloomHdrActive&&(color.pixelFormat==MTLPixelFormatRGBA16Float||color.pixelFormat==MTLPixelFormatRG11B10Float);
    uint32_t info[4]={hdr,waterSrgb(color.pixelFormat),frame,0};
    @autoreleasepool {
        id<MTLComputeCommandEncoder> c=[mc_profiled_compute(enc,mc_frame_cmd(enc),MTLDispatchTypeSerial) autorelease];c.label=@"siliconshaders automatic exposure (2048 points)";
        [c setComputePipelineState:w->sunExposurePipeline];[c setTexture:color atIndex:0];
        [c setBytes:&lighting length:sizeof lighting atIndex:0];[c setBytes:info length:sizeof info atIndex:1];[c setBuffer:w->sunExposure[slot] offset:0 atIndex:2];
        [c dispatchThreadgroups:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(threads,1,1)];[c endEncoding];
    }
    w->sunExposureFrame++;return 1;
}
