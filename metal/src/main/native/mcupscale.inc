// Render resolution (Light → Performance): the world renders into a smaller target and MetalFX's spatial scaler
// enlarges it into the full-size main target before the hand and HUD, which stay at full resolution.
// One scaler per size/format pair; no history, no motion vectors, no CPU readback or wait.
#import <MetalFX/MetalFX.h>
typedef struct {
    id<MTLFXSpatialScaler> scaler;
    id<MTLTexture> input,output; // used only when the source or target lacks the usage MetalFX requires
    NSUInteger inWidth,inHeight,outWidth,outHeight;
    MTLPixelFormat inFormat,outFormat;
    uint64_t runs,copies;
} OwnUpscale;
int mco_upscale_supported(Own *w) { return w&&w->ctx&&[MTLFXSpatialScalerDescriptor supportsDevice:w->ctx->device]; }
void mco_upscale_reset(Own *w) {
    if(!w||!w->upscale)return;OwnUpscale *u=w->upscale;
    [u->scaler release];[u->input release];[u->output release];free(u);w->upscale=NULL;
}
static id<MTLTexture> upscaleImage(Own *w,id<MTLTexture> *slot,NSUInteger width,NSUInteger height,MTLPixelFormat format,MTLTextureUsage usage) {
    if(*slot&&(*slot).width==width&&(*slot).height==height&&(*slot).pixelFormat==format&&((*slot).usage&usage)==usage)return *slot;
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=usage;
    [*slot release];*slot=[w->ctx->device newTextureWithDescriptor:td];return *slot;
}
int mco_upscale(Own *w,Enc *enc,id<MTLTexture> source,id<MTLTexture> target,char *err,int cap) {
 @autoreleasepool {
    if(!w||!enc||!source||!target||source.textureType!=MTLTextureType2D||target.textureType!=MTLTextureType2D)return 0;
    if(source.sampleCount!=1||target.sampleCount!=1||target.storageMode==MTLStorageModeMemoryless||!mco_upscale_supported(w))return 0;
    OwnUpscale *u=w->upscale;
    if(!u){u=calloc(1,sizeof *u);if(!u)return -1;w->upscale=u;}
    if(!u->scaler||u->inWidth!=source.width||u->inHeight!=source.height||u->outWidth!=target.width||u->outHeight!=target.height||
       u->inFormat!=source.pixelFormat||u->outFormat!=target.pixelFormat) {
        [u->scaler release];u->scaler=nil;
        MTLFXSpatialScalerDescriptor *d=[[[MTLFXSpatialScalerDescriptor alloc] init] autorelease];
        d.inputWidth=source.width;d.inputHeight=source.height;d.outputWidth=target.width;d.outputHeight=target.height;
        d.colorTextureFormat=source.pixelFormat;d.outputTextureFormat=target.pixelFormat;
        // The world image is display-encoded; MetalFX also requires perceptual processing for sRGB formats.
        d.colorProcessingMode=MTLFXSpatialScalerColorProcessingModePerceptual;
        u->scaler=[d newSpatialScalerWithDevice:w->ctx->device];
        if(!u->scaler){snprintf(err,cap,"MetalFX spatial scaler %lux%lu -> %lux%lu unavailable",(unsigned long)source.width,(unsigned long)source.height,(unsigned long)target.width,(unsigned long)target.height);return -1;}
        u->inWidth=source.width;u->inHeight=source.height;u->outWidth=target.width;u->outHeight=target.height;u->inFormat=source.pixelFormat;u->outFormat=target.pixelFormat;
    }
    MTLTextureUsage needIn=u->scaler.colorTextureUsage,needOut=u->scaler.outputTextureUsage;
    BOOL copyIn=(source.usage&needIn)!=needIn||source.storageMode==MTLStorageModeMemoryless;
    BOOL copyOut=(target.usage&needOut)!=needOut||target.storageMode!=MTLStorageModePrivate;
    id<MTLTexture> input=copyIn?upscaleImage(w,&u->input,source.width,source.height,source.pixelFormat,needIn|MTLTextureUsageShaderRead):source;
    id<MTLTexture> output=copyOut?upscaleImage(w,&u->output,target.width,target.height,target.pixelFormat,needOut|MTLTextureUsageShaderRead):target;
    if(!input||!output){snprintf(err,cap,"Cannot allocate MetalFX images");return -1;}
    if(!copyIn){[u->input release];u->input=nil;}if(!copyOut){[u->output release];u->output=nil;}
    BOOL resume=enc->render!=nil;id<MTLTexture> resumeColor=enc->colorCount==1?enc->colors[0]:nil,resumeDepth=enc->depth;
    id<MTLCommandBuffer> command=mc_frame_cmd(enc);
    if(copyIn){
        id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"render scale: world image for MetalFX";
        [b copyFromTexture:source sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(source.width,source.height,1)
            toTexture:input destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[b endEncoding];[b release];
    }
    u->scaler.colorTexture=input;u->scaler.outputTexture=output;
    u->scaler.inputContentWidth=source.width;u->scaler.inputContentHeight=source.height;
    [u->scaler encodeToCommandBuffer:command];
    if(copyOut){
        id<MTLBlitCommandEncoder> b=mc_profiled_blit(enc,command);b.label=@"render scale: MetalFX result into the main image";
        [b copyFromTexture:output sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(target.width,target.height,1)
            toTexture:target destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[b endEncoding];[b release];u->copies++;
    }
    if(resume&&resumeColor){id<MTLTexture> targets[]={resumeColor};float load[5]={0};mc_render_begin(enc,1,targets,load,resumeDepth,0,0,resumeColor.width,resumeColor.height);}
    w->lastPso=nil;u->runs++;return 1;
 }
}
void mco_upscale_debug_stats(Own *w,uint64_t *out) {
    out[0]=out[1]=0;if(!w||!w->upscale)return;OwnUpscale *u=w->upscale;out[0]=u->runs;out[1]=u->copies;
}
