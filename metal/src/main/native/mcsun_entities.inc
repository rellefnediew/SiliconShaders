// Shared world light and shadows, applied in the object's existing draw. No new passes, render targets or GPU readback:
// the fragment stage reads this frame's existing cascade and glass maps.
typedef struct {uint8_t sun[144];uint8_t blend[320];float glass[24];} OwnSunEntityFrame;
_Static_assert(sizeof(OwnSunEntityFrame)==560,"Entity frame ABI");
// Never sampled (lighting or shadows off), but every statically used argument needs a texture under Metal validation.
static id<MTLTexture> sunEntityPlaceholder(id<MTLDevice> device,BOOL depth){
    static id<MTLTexture> placeholders[2];id<MTLTexture> *slot=&placeholders[depth?1:0];
    if(!*slot){
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:depth?MTLPixelFormatDepth32Float:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
        td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead;*slot=[device newTextureWithDescriptor:td];
    }
    return *slot;
}
typedef struct {simd_float4x4 model;simd_float3x3 normal;} OwnSunEntityModel;
_Static_assert(sizeof(OwnSunEntityModel)==112,"Entity model ABI");
void mco_sun_entity_view(Own *w,const void *inverse) {
    w->sunEntityViewValid=0;if(!inverse)return;
    const float *v=inverse;for(int i=0;i<16;i++)if(!isfinite(v[i]))return;
    memcpy(&w->sunEntityInverseView,inverse,64);w->sunEntityViewValid=1;
}
void mco_sun_entity_bind(Own *w,Enc *enc,int world,id<MTLBuffer> transforms,int64_t offset) {
    if(!enc||!enc->render)return;
    OwnSunLighting lighting={0};OwnSunEntityFrame frame={0};BOOL shadows=NO;
    OwnSunEntityModel model={.model=matrix_identity_float4x4,.normal=matrix_identity_float3x3};
    if(w&&world&&w->sunActive&&w->sunLastLighting&&w->sunEntityViewValid&&enc->colorCount==1&&enc->colors[0]==w->sunEntityTarget&&
       transforms&&transforms.contents&&offset>=0&&(uint64_t)offset+64<=transforms.length) {
        simd_float4x4 view;memcpy(&view,(const uint8_t *)transforms.contents+offset,64);
        model.model=simd_mul(w->sunEntityInverseView,view);
        simd_float3x3 basis={.columns={model.model.columns[0].xyz,model.model.columns[1].xyz,model.model.columns[2].xyz}};
        model.normal=simd_transpose(simd_inverse(basis));BOOL finite=YES;
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)finite&=isfinite(model.model.columns[c][r]);
        for(int c=0;c<3;c++)for(int r=0;r<3;r++)finite&=isfinite(model.normal.columns[c][r]);
        if(finite){
            const float *look=w->sunLookSet?w->sunLook:(const float[]){1,1,1};
            lighting.enabled=1;lighting.srgb=enc->colors[0].pixelFormat==MTLPixelFormatRGBA8Unorm_sRGB||enc->colors[0].pixelFormat==MTLPixelFormatBGRA8Unorm_sRGB;
            lighting.exposure=look[0];lighting.saturation=look[1];lighting.contrast=look[2];sunLightingFill(w,&lighting);
            memcpy(frame.sun,w->sunFrame,sizeof frame.sun);memcpy(frame.blend,w->sunBlendFrame,sizeof frame.blend);memcpy(frame.glass,w->sunGlassParams,sizeof frame.glass);
            shadows=w->sunMap!=nil;
        }else model=(OwnSunEntityModel){.model=matrix_identity_float4x4,.normal=matrix_identity_float3x3};
    }
    // Metal snapshots per-draw bytes, including the model/normal transforms; in-flight frames never share mutable storage.
    [enc->render setVertexBytes:&model length:sizeof model atIndex:24];
    if(w)blockLightBind(w,enc->render);
    else [enc->render setFragmentBuffer:blockLightEmpty(enc->ctx->device) offset:0 atIndex:28];
    [enc->render setFragmentBytes:&lighting length:sizeof lighting atIndex:24];
    [enc->render setFragmentBytes:&frame length:sizeof frame atIndex:25];
    // Same cascade order as the terrain's forward path; one call binds shadow, glass depth and glass color maps.
    id<MTLTexture> depthPlaceholder=sunEntityPlaceholder(enc->ctx->device,YES),colorPlaceholder=sunEntityPlaceholder(enc->ctx->device,NO),maps[12];
    for(int cascade=0;cascade<4;cascade++){
        id<MTLTexture> map=!shadows?nil:cascade==0?w->sunMap:(cascade==w->sunCascaded-1||(!w->sunCascaded&&cascade==1))?w->sunSecondMap:cascade<3?w->sunMiddleMaps[cascade-1]:nil;
        maps[cascade]=map?:shadows?w->sunMap:depthPlaceholder;
        maps[4+cascade]=shadows&&w->sunGlassDepth[cascade]?w->sunGlassDepth[cascade]:maps[cascade];
        maps[8+cascade]=shadows&&w->sunGlassColor[cascade]?w->sunGlassColor[cascade]:shadows&&w->sunGlassWhite?w->sunGlassWhite:colorPlaceholder;
    }
    [enc->render setFragmentTextures:maps withRange:NSMakeRange(24,12)];
}
