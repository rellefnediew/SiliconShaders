static void sunGlassClear(Own *w) {
    for(int i=0;i<4;i++){[w->sunGlassDepth[i] release];w->sunGlassDepth[i]=nil;[w->sunGlassColor[i] release];w->sunGlassColor[i]=nil;}
    memset(w->sunGlassParams,0,sizeof w->sunGlassParams);
}
// Only glass uses these lower-resolution transmission targets. Opaque/entity shadows keep their existing depth maps.
static BOOL sunGlassBounds(const OwnSunCaster *batch,const simd_float4x4 *clip,simd_float4 *bounds) {
    simd_float3 lo=batch->boundsLo.xyz,hi=batch->boundsHi.xyz;
    simd_float3 minimum=(simd_float3){INFINITY,INFINITY,INFINITY},maximum=-minimum;
    for(int corner=0;corner<8;corner++) {
        simd_float4 q=simd_mul(*clip,(simd_float4){corner&1?hi.x:lo.x,corner&2?hi.y:lo.y,corner&4?hi.z:lo.z,1});
        minimum=simd_min(minimum,q.xyz);maximum=simd_max(maximum,q.xyz);
    }
    if(minimum.x>1||maximum.x< -1||minimum.y>1||maximum.y< -1||minimum.z>1||maximum.z<0)return NO;
    bounds->x=fminf(bounds->x,minimum.x*.5f+.5f);bounds->y=fminf(bounds->y,minimum.y*.5f+.5f);
    bounds->z=fmaxf(bounds->z,maximum.x*.5f+.5f);bounds->w=fmaxf(bounds->w,maximum.y*.5f+.5f);return YES;
}

static int sunGlassUpdate(Own *w,Enc *enc,const void *packet,int cascaded,char *err,int cap) {
    memset(w->sunGlassParams,0,sizeof w->sunGlassParams);
    if(!w->sunGlassWhite) {
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
        td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
        w->sunGlassWhite=[w->ctx->device newTextureWithDescriptor:td];uint32_t white=0xffffffff;
        if(!w->sunGlassWhite){snprintf(err,cap,"Cannot allocate glass fallback");return -1;}
        [w->sunGlassWhite replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:&white bytesPerRow:4];
    }
    for(int cascade=0;cascade<(cascaded?cascaded:1);cascade++) {
        const uint8_t *frame=(const uint8_t *)packet+(cascade?256+(cascade-1)*144:0);
        uint32_t resolution=*(const uint32_t *)(frame+136);if(!resolution)continue;
        simd_float4x4 clip;memcpy(&clip,frame,64);simd_float4 bounds={1,1,0,0};BOOL present=NO;
        for(int i=0;i<w->sunCasterCount;i++)if(w->sunCasters[i].config.x>0.5f&&sunCasterVisible(w,w->sunCasters[i].boundsLo.xyz,w->sunCasters[i].boundsHi.xyz))present|=sunGlassBounds(w->sunCasters+i,&clip,&bounds);
        if(!present)continue;
        int size=MAX(256,(int)resolution/2);
        if(!w->sunGlassDepth[cascade]||w->sunGlassDepth[cascade].width!=size) {
            id<MTLTexture> depth=sunTexture(w,size,err,cap);if(!depth)return -1;
            MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:size height:size mipmapped:NO];
            td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
            id<MTLTexture> color=[w->ctx->device newTextureWithDescriptor:td];
            if(!color){[depth release];snprintf(err,cap,"Cannot allocate glass transmission");return -1;}
            [w->sunGlassDepth[cascade] release];[w->sunGlassColor[cascade] release];w->sunGlassDepth[cascade]=depth;w->sunGlassColor[cascade]=color;
        }
        if(!w->sunGlassPipeline) {
            NSError *issue=nil;id<MTLFunction> vs=[w->library newFunctionWithName:@"sun_actor_vs"],fs=[w->library newFunctionWithName:@"sun_glass_fs"];
            MTLRenderPipelineDescriptor *pd=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];pd.label=@"siliconshaders glass light transmission";
            pd.vertexFunction=vs;pd.fragmentFunction=fs;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
            w->sunGlassPipeline=vs&&fs?[w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue]:nil;[vs release];[fs release];
            if(!w->sunGlassPipeline){ownErr(issue,"glass shadow pipeline",err,cap);return -1;}
        }
        mc_pre_end_encoders(enc);id<MTLCommandBuffer> command=w->sunDeferred?mc_frame_cmd(enc):mc_pre(enc);MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
        rp.depthAttachment.texture=w->sunGlassDepth[cascade];rp.depthAttachment.clearDepth=1;rp.depthAttachment.loadAction=MTLLoadActionClear;rp.depthAttachment.storeAction=MTLStoreActionStore;
        rp.colorAttachments[0].texture=w->sunGlassColor[cascade];rp.colorAttachments[0].clearColor=MTLClearColorMake(1,1,1,1);
        rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> render=mc_profiled_render(enc,command,rp);
        if(!render){snprintf(err,cap,"Cannot begin glass shadow pass");return -1;}
        render.label=enc->samples?[NSString stringWithFormat:@"sun glass level %d (%d)",cascade,size]:@"sun glass";
        [render setRenderPipelineState:w->sunGlassPipeline];[render setDepthStencilState:w->sunDepth];[render setCullMode:MTLCullModeNone];
        [render setDepthBias:1 slopeScale:1.5 clamp:0.005];[render setVertexBytes:frame length:144 atIndex:0];
        for(int i=0;i<w->sunCasterCount;i++) {
            OwnSunCaster *batch=w->sunCasters+i;simd_float4 ignored={1,1,0,0};
            if(batch->config.x<0.5f||batch->draw.x<=0||!sunCasterVisible(w,batch->boundsLo.xyz,batch->boundsHi.xyz)||!sunGlassBounds(batch,&clip,&ignored))continue;
            [render setVertexBuffer:(id<MTLBuffer>)(uintptr_t)batch->vertices offset:0 atIndex:1];[render setVertexBytes:batch length:sizeof *batch atIndex:5];
            [render setFragmentTexture:(id<MTLTexture>)(uintptr_t)batch->texture atIndex:0];[render setFragmentSamplerState:(id<MTLSamplerState>)(uintptr_t)batch->sampler atIndex:0];
            if(w->sunDeferred)[render drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:w->sunActorArgs indirectBufferOffset:((NSUInteger)cascade*w->sunCasterCount+i)*32];
            else [render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:(batch->draw.x/4)*6];
        }
        [render endEncoding];[render release];memcpy(w->sunGlassParams+cascade*4,&bounds,16);
        w->sunGlassParams[16+cascade]=1;w->sunGlassParams[20+cascade]=1.0f/size;
    }
    for(int i=MAX(1,cascaded);i<4;i++){[w->sunGlassDepth[i] release];w->sunGlassDepth[i]=nil;[w->sunGlassColor[i] release];w->sunGlassColor[i]=nil;}
    return 0;
}
