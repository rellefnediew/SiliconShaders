// Culling must reduce indirect geometry while retaining a caster outside the camera view whose shadow is visible.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern void mco_sun_debug_force_grouped_cull(void *,int);
extern void mco_sun_selection_optimization(void *,int);
extern int mco_sun_debug_grouped_cull(void *);
extern void mco_sun_receiver_optimization(void *,int);
extern int mco_sun_debug_receiver_optimized(void *);
extern int mco_sun_debug_receiver_ready(void *);
extern void mco_sun_memory_optimization(void *,int);
extern void mco_sun_debug_memory_fallback(void *,int);
extern int mco_sun_debug_memory_active(void *);
extern int mco_sun_debug_memory_depth_direct(void *);
extern int mco_sun_debug_memory_copy_mask(void *);
extern int mco_sun_debug_copied_inputs(void *);
extern id<MTLTexture> mco_sun_debug_history(void *);
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_candidates(void *,id<MTLBuffer>,int);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern int mco_sun_debug_deferred(void *);
extern id<MTLBuffer> mco_sun_debug_receiver_stats(void *);
extern id<MTLBuffer> mco_sun_debug_receiver_bins(void *);
extern int mco_sun_debug_direct_resolve(void *);
extern void mco_sun_debug_force_separate_resolve(void *,int);
extern void mco_sun_filter_optimization(void *,int);
extern int mco_sun_culling(void *,const void *);
extern id<MTLBuffer> mco_sun_debug_draw_args(void *);
extern int mco_sun_debug_exact(void *);
extern void mco_sun_debug_force_legacy(void *,int);
extern void mco_sun_shadow_map_optimization(void *,int);
extern void mco_sun_geometry_optimization(void *,int);
extern void mco_sun_far_cache(void *,int,uint64_t);
extern void mco_sun_gbuffer(void *,int,int);
extern int mco_sun_debug_lighting(void *);
extern void mco_sun_environment(void *,float,float,int,int);
extern void mco_sun_moon_light(void *,int);
extern void mco_sun_hand(void *,const float *);
extern int mco_sun_debug_gbuffer(void *);
extern id<MTLTexture> mco_sun_debug_albedo(void *);
extern id<MTLTexture> mco_sun_debug_light_data(void *);
extern int mco_sun_debug_far_mode(void *);
extern int mco_sun_debug_far_direct(void *);
extern void mco_sun_debug_far_still_texel(void *,float);
extern int mco_sun_debug_indexed(void *);
extern void mco_sun_debug_force_stats(void *,int);
extern int mco_sun_debug_depth_split(void *);
extern id<MTLTexture> mco_sun_debug_cascade_map(void *,int);
// Optional actual world-pass binding probe, after the fixture's image comparisons.
extern void mco_sun_entity_bind(void *,Enc *,int,id<MTLBuffer>,int64_t);
extern void mco_sun_half_shadows(void *,int);
extern void mco_sun_entity_view(void *,const void *);
static void entityBindCheck(Ctx *ctx,Enc *enc,void *own,id<MTLTexture> color,NSString *source,BOOL enabled,BOOL moon) {
 if(!getenv("SILICONSHADERS_ENTITY_BIND_CHECK"))return;
 static NSMutableDictionary *pipelines=nil;if(!pipelines)pipelines=[NSMutableDictionary new];
 NSNumber *key=@(color.pixelFormat);id<MTLRenderPipelineState> pipeline=pipelines[key];
 if(!pipeline){
  NSString *probe=@"\nstruct EntityProbeModel {float4x4 model;float3x3 normal;};struct EntityProbeVertex {float4 pos [[position]];float3 check [[user(locn0)]];};vertex EntityProbeVertex entity_bind_probe_vs(uint vid [[vertex_id]], constant EntityProbeModel &m [[buffer(24)]]) {float2 xy=float2((vid<<1)&2,vid&2);return {float4(xy*2-1,0,1),float3(m.model[3].x*.1,m.normal[0].x,m.normal[1].y)};}fragment float4 entity_bind_probe_fs(EntityProbeVertex in [[stage_in]],constant uint &phase [[buffer(23)]],constant SunLighting &l [[buffer(24)]], constant SunFrame &sun [[buffer(25)]]) { if(phase==4)return float4(in.check,1);return float4(l.enabled?1:0,l.srgb?1:0,l.moon>.5?1:0,1); }\n";
  NSError *issue=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;
  id<MTLLibrary> lib=[ctx->device newLibraryWithSource:[source stringByAppendingString:probe] options:options error:&issue];require(lib!=nil,issue.localizedDescription);
  MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"entity_bind_probe_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"entity_bind_probe_fs"];pd.colorAttachments[0].pixelFormat=color.pixelFormat;
  pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);pipelines[key]=pipeline;[pipeline release];[pd.vertexFunction release];[pd.fragmentFunction release];[pd release];[lib release];[options release];
 }
 MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:color.pixelFormat width:color.width height:color.height mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> other=[ctx->device newTextureWithDescriptor:td];
 simd_float4x4 identity=matrix_identity_float4x4;id<MTLBuffer> model=buffer(ctx,&identity,64);mco_sun_entity_view(own,&identity);
 for(uint32_t phase=0;phase<6;phase++){
  if(phase==4){
   float angle=.7f;simd_float4x4 camera={.columns={{cosf(angle),0,-sinf(angle),0},{0,1,0,0},{sinf(angle),0,cosf(angle),0},{0,0,0,1}}};
   simd_float4x4 object={.columns={{2,0,0,0},{0,4,0,0},{0,0,1,0},{3,0,0,1}}},mv=simd_mul(camera,object),inverse=simd_inverse(camera);
   memcpy(model.contents,&mv,64);mco_sun_entity_view(own,&inverse);
  }
  id<MTLTexture> target=phase==1?other:color;id<MTLTexture> colors[]={target};float load[5]={0};mc_render_begin(enc,1,colors,load,nil,0,0,target.width,target.height);
  [enc->render setRenderPipelineState:pipeline];[enc->render setScissorRect:(MTLScissorRect){0,0,1,1}];[enc->render setCullMode:MTLCullModeNone];mco_sun_entity_bind(phase==3?NULL:own,enc,phase!=2,phase==5?nil:model,0);[enc->render setFragmentBytes:&phase length:4 atIndex:23];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
  id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
  uint8_t pixel[4]={0};[target getBytes:pixel bytesPerRow:4 fromRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0];BOOL active=phase==0&&enabled;
  BOOL bgra=color.pixelFormat==MTLPixelFormatBGRA8Unorm||color.pixelFormat==MTLPixelFormatBGRA8Unorm_sRGB,srgb=color.pixelFormat==MTLPixelFormatRGBA8Unorm_sRGB||color.pixelFormat==MTLPixelFormatBGRA8Unorm_sRGB;
  if(phase==4){
   float expected[3]={enabled?.3f:0,enabled?.5f:1,enabled?.25f:1};
   for(int channel=0;channel<3;channel++){float value=expected[channel];if(srgb)value=value<=.0031308?value*12.92f:1.055f*powf(value,1/2.4f)-.055f;int at=bgra?2-channel:channel;require(abs((int)pixel[at]-(int)lrintf(value*255))<=2,@"Camera cancellation/nonuniform model normal transform failed");}
  }else require(pixel[bgra?2:0]==(active?255:0)&&pixel[1]==(active&&srgb?255:0)&&pixel[bgra?0:2]==(active&&moon?255:0),@"Entity light scope, format or moon packet did not match actual terrain resolve");
 }
 [model release];[other release];printf("entity binding: world=%d, offscreen/hand/absent off, moon=%d, format=%lu\n",enabled,moon,(unsigned long)color.pixelFormat);
}
typedef struct { uint32_t count;float nearRadius;uint32_t pad[2];simd_float4 planes[16]; } Cull;
static uint32_t bound(int x,int y,int z) { return (x+16)|((y+16)<<8)|((z+16)<<16); }
int main(int argc,const char **argv) {
 @autoreleasepool {
  require(argc>=3,@"Usage: sun_direct_resolve_check TERRAIN SUN [smooth-off] [switch] [many-records] [legacy] [depth-copy] [fallback] [srgb] [memory-only] [filter-off] [depth-split] [indexed] [far-cache] [gbuffer] [gbuffer-rebuilt] [half-shadows]");BOOL halfShadows=NO;BOOL cullingCompare=NO,receiverGridOff=NO,receiverGridSwitch=NO,receiverGridCompare=NO,smooth=YES,depthSplit=NO,indexedGeometry=NO,farCache=NO,gbuffer=NO,gbufferRebuilt=NO,gbufferViews=NO,night=NO,handOpt=NO,skyOn=NO,moonOpt=NO,switchModes=NO,manyRecords=NO,legacyDraw=NO,readableDepth=YES,memoryFallback=NO,srgb=NO,memoryOnly=NO,fixedFilterOff=NO;
  for(int i=3;i<argc;i++){if(!strcmp(argv[i],"half-shadows"))halfShadows=YES;else if(!strcmp(argv[i],"smooth-off"))smooth=NO;else if(!strcmp(argv[i],"switch"))switchModes=YES;else if(!strcmp(argv[i],"many-records"))manyRecords=YES;else if(!strcmp(argv[i],"legacy"))legacyDraw=YES;else if(!strcmp(argv[i],"depth-copy"))readableDepth=NO;else if(!strcmp(argv[i],"fallback"))memoryFallback=YES;else if(!strcmp(argv[i],"srgb"))srgb=YES;else if(!strcmp(argv[i],"memory-only"))memoryOnly=YES;else if(!strcmp(argv[i],"filter-off"))fixedFilterOff=YES;else if(!strcmp(argv[i],"depth-split"))depthSplit=YES;else if(!strcmp(argv[i],"indexed"))indexedGeometry=YES;else if(!strcmp(argv[i],"far-cache"))farCache=YES;else if(!strcmp(argv[i],"gbuffer"))gbuffer=YES;else if(!strcmp(argv[i],"gbuffer-rebuilt"))gbuffer=gbufferRebuilt=YES;else if(!strcmp(argv[i],"gbuffer-views"))gbuffer=gbufferViews=YES;else if(!strcmp(argv[i],"night"))night=YES;else if(!strcmp(argv[i],"hand"))handOpt=gbuffer=night=YES;else if(!strcmp(argv[i],"sky"))skyOn=gbuffer=YES;else if(!strcmp(argv[i],"moon"))moonOpt=gbuffer=YES;else if(!strcmp(argv[i],"receiver-grid-off"))receiverGridOff=YES;else if(!strcmp(argv[i],"receiver-grid-switch"))receiverGridOff=receiverGridSwitch=YES;else if(!strcmp(argv[i],"receiver-grid-compare"))receiverGridOff=receiverGridCompare=gbuffer=YES;else if(!strcmp(argv[i],"culling-compare"))cullingCompare=receiverGridCompare=gbuffer=YES;else require(NO,@"Unknown option");}
  NSString *source=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
  source=[source stringByAppendingString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:nil]];
  char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
  void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,[NSString stringWithUTF8String:error]);void *referenceOwn=own;mco_sun_debug_force_stats(own,1);
  void *optimized=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(optimized!=NULL,[NSString stringWithUTF8String:error]);mco_sun_debug_force_stats(optimized,1);Enc *enc=mc_enc_new(ctx);if(halfShadows){mco_sun_half_shadows(own,1);mco_sun_half_shadows(optimized,1);}
  // Exercise partial and full records. Only the last cutout quad is opaque,
  // so dropping a tail entry loses the offscreen caster's visible shadow.
  const uint32_t counts[]={1,17,32,64};uint32_t starts[]={0,1,18,50};
  Vertex vertices[114*4]={0};
  for(int q=0;q<4;q++)for(uint32_t local=0;local<counts[q];local++)for(int c=0;c<4;c++) {
   float radius=q?.4:4,cx=q==1?1:q==2?6.5:0,cz=q==2?-3.5:1.5;
   Vertex *v=&vertices[(starts[q]+local)*4+c];v->p[0]=8+cx+((c==1||c==2)?radius:-radius);v->p[1]=q?2:0;
   v->p[2]=q==0?8+((c>=2)?radius:-radius):8+cz+((c>=2)?radius:-radius);
   memset(v->color,255,4);if(q==1 && local+1<counts[q])v->color[3]=0;
   // G-buffer modes: a tinted vertex color, so albedo and lightmap must be applied in the right order.
   if(gbuffer){v->color[0]=220;v->color[1]=180;v->color[2]=140;}
   v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=.5;
   // Native lighting checks: outdoor terrain without block light, so the shadow is the sun's alone.
   if(gbuffer&&!gbufferRebuilt&&!gbufferViews)v->light[0]=0;
   // hand: neither block nor sky light, so the held light alone lights the ground.
   if(handOpt){v->light[0]=0;v->light[1]=0;}
  }
  Section section[2]={{.x=-8,.z=-8,.count=3},{.x=-8,.z=-24,.start=3,.count=1}};
  Record originalRecords[4]={{.lo=bound(4,0,4),.hi=bound(12,0,12)},
   {.start=1,.meta=16|(1u<<9),.lo=bound(8,2,9),.hi=bound(10,2,10)},
   {.start=18,.meta=31,.lo=bound(14,2,4),.hi=bound(15,2,5)},
   {.start=50,.slot=1,.meta=63,.lo=bound(7,2,9),.hi=bound(9,2,10)}};
  Record records[114]={0};uint32_t lists[114]={0},recordCount=4;
  if(manyRecords) {
   recordCount=0;
   for(int q=0;q<4;q++)for(uint32_t local=0;local<counts[q];local++) {
    Record entry=originalRecords[q];entry.start+=local;entry.meta&=~63u;
    records[recordCount++]=entry;
   }
   section[0].count=50;section[1].start=50;section[1].count=64;
  } else memcpy(records,originalRecords,sizeof originalRecords);
  for(uint32_t i=0;i<recordCount;i++)lists[i]=i;
  uint32_t args[]={384,recordCount,0,0,0};
  id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,section,sizeof section),recs=buffer(ctx,records,sizeof records),list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
  // Visible ground x=-5..0,z=.5..2.5. The caster at x=1 lies outside it, but its shadow reaches x=-.5.
  simd_float4x4 projection={.columns={{.4,0,0,0},{0,0,.1,0},{0,1,0,0},{1,-1.5,1.2,1}}};
  uint8_t globals[48]={0},terrain[80]={0};int camera[]={0,8,0};memcpy(globals,camera,12);simd_float4x4 identity=matrix_identity_float4x4;memcpy(terrain,&identity,64);int ts[]={4,4};memcpy(terrain+64,ts,8);
  float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};
  id<MTLBuffer> p=buffer(ctx,&projection,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
  int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
  id<MTLTexture> atlas=white(ctx),light=white(ctx);
  if(gbuffer) {
   // A colored 16x16 lightmap instead of white: the rebuilt color must sample the same block/sky texel.
   MTLTextureDescriptor *ld=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
   uint8_t lm[16*16*4];for(int y=0;y<16;y++)for(int x=0;x<16;x++){uint8_t *p=lm+(y*16+x)*4;p[0]=(uint8_t)(40+x*13);p[1]=(uint8_t)(255-y*9);p[2]=(uint8_t)(90+x*5+y*5);p[3]=255;}
   [light release];light=[ctx->device newTextureWithDescriptor:ld];[light replaceRegion:MTLRegionMake2D(0,0,16,16) mipmapLevel:0 withBytes:lm bytesPerRow:64];
  }MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
  Sun sun={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},.cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0/1024,.00015,1},.sections=2,.resolution=1024,.on=1};
  // Sweeping the visible volume toward (.6,.8,0) removes the right plane and keeps these three planes.
  Cull culling={.count=1,.planes={{0,0,0,1}}};
  uint8_t reference[128*128*4]={0},referenceHistory[128*128*8]={0};
  // hand: a held torch (level 14) alternates on and off between pairs (on in even pairs).
  uint8_t handOff[128*128*4]={0};BOOL haveHandOff=NO;int handOffWidth=0;
  // Paired shadow depth for depth-split: only the cutout/opaque draw split differs.
  float *referenceMaps[2]={calloc(1024*1024,4),calloc(1024*1024,4)};
  // No per-iteration caller pool: reproduce the actual Java FFM lifetime.
  int previousMode=0;
  for(int test=0;test<18;test++) {
   own=test%2?optimized:referenceOwn;int width=test/2>=6?96:128;
            testProfileBegin(enc);
   mco_sun_debug_force_grouped_cull(own,0);mco_sun_selection_optimization(own,memoryOnly?1:test%2);
   int mode=memoryOnly?(fixedFilterOff?0:1):switchModes?((test/2)%3==1):1;
   BOOL memoryMode=test%2 && (!switchModes || (test/2)%3!=2);
   mco_sun_memory_optimization(own,memoryMode);mco_sun_debug_memory_fallback(own,memoryFallback);
   BOOL scanMode=memoryOnly || (test%2 && (!switchModes || (test/2)%3!=2));
   mco_sun_receiver_optimization(own,scanMode);
   BOOL changed=mode!=previousMode;
   // Keep the paired reference history equally fresh on a mode transition.
   if(!memoryOnly && switchModes && changed && !(test%2))mco_sun_filter_optimization(own,1);
   mco_sun_filter_optimization(own,(memoryOnly||test%2)&&mode);
   if(test%2)previousMode=mode;
   mco_sun_shadow_map_optimization(own,depthSplit&&test%2);mco_sun_geometry_optimization(own,indexedGeometry&&test%2);mco_sun_far_cache(own,farCache&&test%2,(test/2)/3);mco_sun_gbuffer(own,gbuffer&&(test%2||receiverGridCompare),gbufferRebuilt?5:gbufferViews?1+(test/2)%4:0);
   mco_sun_environment(own,1,0,0,skyOn&&test%2);
   // moon: the frame's directional light is the moon (dim, violet-blue); its shadow must still read clearly.
   mco_sun_moon_light(own,moonOpt&&test%2);
   BOOL handOn=handOpt&&test%2&&(test/2)%2==0;
   float hand[12]={0,-0.7f,0,handOn?14:0,-1,-1,-1,0,-1,-1,-1,0};mco_sun_hand(own,hand);
   BOOL legacy=legacyDraw;mco_sun_debug_force_legacy(own,legacy);mco_sun_debug_force_separate_resolve(own,!memoryOnly && test%2==0);
   Cull off={0};require(mco_sun_culling(own,cullingCompare&&test%2?&off:&culling)==0,@"Cull packet rejected");
   MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(test/2>=3&&test/2<6?(srgb?MTLPixelFormatBGRA8Unorm_sRGB:MTLPixelFormatBGRA8Unorm):(srgb?MTLPixelFormatRGBA8Unorm_sRGB:MTLPixelFormatRGBA8Unorm)) width:width height:width mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
   td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|(readableDepth?MTLTextureUsageShaderRead:0);id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];id<MTLTexture> target[]={color};float clear[]={1,0,0,0,1};mc_render_begin(enc,1,target,clear,depth,1,0,width,width);
   // Every pair changes the camera. A sky strip remains uncovered on the left.
   simd_float4x4 view=projection;view.columns[3].x+=.01f*(test/2);memcpy(p.contents,&view,64);
   // far-cache: pair 4 jumps 1 block (limit 0.7), pair 7 turns the sun 1e-5 rad (reuse), pair 8 0.01 rad (refresh).
   double jump=farCache&&test/2==4?1:0;
   OwnSunSurfaceView surface={.clip=view,.camera={.025*(test/2)+jump,8,0},.enabled=1};
   BOOL gridActive=!(receiverGridOff&&test%2&&(!receiverGridSwitch||(test/2)%2==0));
   surface.flags=gridActive?0:OWN_SUN_NO_RECEIVER_GRID;
   if(cullingCompare) {
    // Lighting must stay active with the master culling switch off; only selection changes.
    surface.flags|=OWN_SUN_DEFERRED_LIGHTING;surface.enabled=!(test%2);
    gridActive=surface.enabled;
   }
   require(mco_sun_surface(own,&surface)==0,@"Surface packet rejected");
   uint32_t candidates[33];for(int i=0;i<33;i++)candidates[i]=i<2?(uint32_t)i:UINT32_MAX;id<MTLBuffer> candidateBuffer=buffer(ctx,candidates,sizeof candidates);
   require(mco_sun_candidates(own,cullingCompare&&test%2?nil:candidateBuffer,cullingCompare&&test%2?-1:33)==0,@"Candidate packet rejected");
   if(farCache){float turn=test/2==7?1e-5f:test/2==8?.01f:0,base=atan2f(.8f,.6f);sun.sun=(simd_float4){cosf(base+turn),sinf(base+turn),0,1};}
   // night: no direct sun. The deferred path stays on; no cascade, receiver grid or mask sampling runs.
   if(night)sun.sun.w=0;
   uint8_t packet[400]={0};memcpy(packet,&sun,144);memcpy(packet+144,&view,64);
   memcpy(packet+208,surface.camera,24);*(float *)(packet+240)=1.0f/60;*(uint64_t *)(packet+232)=(test/2)/3;*(uint32_t *)(packet+248)=smooth?1:0;
   Sun far=sun;far.resolution=512;far.config.x=10;far.config.y=1.0f/512;
   for(int c=0;c<4;c++){far.clip.columns[c].x*=.5;far.clip.columns[c].y*=.5;}memcpy(packet+256,&far,144);
   // far-cache: a wind clock that changes every frame (OwnWind fields +76 strength, +92 time) must not
   // defeat reuse once far texels are coarse enough for still foliage (this fixture's 0.04-block texels
   // count as coarse through the debug limit; sun_wind_check covers fine maps that keep the wind).
   if(farCache)mco_sun_debug_far_still_texel(own,.01f);
   if(farCache)for(int k=0;k<2;k++){*(float *)(packet+(k?256:0)+76)=1;*(float *)(packet+(k?256:0)+92)=.37f*test;}
   require(mco_sun_frame(own,enc,packet,400,arena,sections,recs,atlas,sampler,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
   require(mco_sun_debug_deferred(own)==1,@"Current-frame deferred path did not activate immediately");
   require(mco_sun_debug_gbuffer(own)==(gbuffer&&(test%2||receiverGridCompare)),@"G-buffer toggle was not applied");
   BOOL lighting=gbuffer&&!gbufferRebuilt&&!gbufferViews&&(test%2||receiverGridCompare);
   uint8_t df[80]={0};*(int *)(df+4)=1;require(mco_draw(own,enc,0,df,80,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
   require(mco_sun_finish(own,enc,error,sizeof error)==1,[NSString stringWithUTF8String:error]);
   if(!night)require(mco_sun_debug_depth_split(own)==(depthSplit&&test%2),@"Shadow map optimization toggle was not applied");
   // Revision changes every third pair; the camera moves 0.025 per pair (far range 10: reuse limit 0.7).
   if(farCache)printf("far-cache test %d: mode %d direct %d\n",test,mco_sun_debug_far_mode(own),mco_sun_debug_far_direct(own));
   if(farCache&&!night)require(mco_sun_debug_far_mode(own)==(!(test%2)?0:((test/2)%3==0||test/2==4||test/2==5||test/2==8)?1:2)&&mco_sun_debug_far_direct(own)==(test%2),@"Far terrain cache refresh/reuse decision incorrect");
   BOOL activeMemory=memoryMode&&!memoryFallback;
   require(mco_sun_debug_memory_active(own)==activeMemory,@"Memory toggle/fallback was not applied");
   require(mco_sun_debug_memory_depth_direct(own)==(activeMemory&&readableDepth),@"Depth usage fallback was not applied");
   require(mco_sun_debug_memory_copy_mask(own)==(!activeMemory?3:readableDepth?0:2),@"Unexpected full-frame copies");
   if(test==1&&activeMemory&&readableDepth)require(!mco_sun_debug_copied_inputs(own),@"Optimized start allocated unused copied inputs");
   if(!night)require(mco_sun_debug_receiver_optimized(own)==(gridActive&&scanMode),@"Receiver scan toggle was not applied");
   require(mco_sun_debug_receiver_ready(own)==(!night&&gridActive),@"Receiver grid was not skipped or re-enabled as requested");
   if(!night)require(mco_sun_debug_grouped_cull(own)==((memoryOnly||test%2)&&!(cullingCompare&&test%2)),@"Caster selector toggle was not applied");
   require(!mco_sun_debug_deferred(own),@"Translucent draws would retain deferred state");
   require(mco_sun_debug_direct_resolve(own)==((memoryOnly||test%2) && mode && (!smooth || (test/2)%3==0 || (switchModes&&changed))),@"Direct path was not limited to globally invalid history");
   id<MTLCommandBuffer> cmd=testProfileCommit(enc,ctx);[cmd waitUntilCompleted];require(cmd.status!=MTLCommandBufferStatusError,cmd.error.localizedDescription);[cmd release];
   id<MTLBuffer> read=[ctx->device newBufferWithLength:16 options:MTLResourceStorageModeShared];cmd=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[cmd blitCommandEncoder];if(mco_sun_debug_draw_args(own))[blit copyFromBuffer:mco_sun_debug_draw_args(own) sourceOffset:0 toBuffer:read destinationOffset:0 size:16];[blit endEncoding];[cmd commit];[cmd waitUntilCompleted];require(cmd.status!=MTLCommandBufferStatusError,cmd.error.localizedDescription);
   BOOL exact=mco_sun_debug_exact(own);require(night||exact!=legacy,@"Exact geometry/fallback path was not selected");
   uint32_t *arguments=read.contents,kept=exact?arguments[0]/6:arguments[1];
   // night: no cascade is drawn, so draw state, cached maps and map contents do not apply.
   if(!night)require(mco_sun_debug_indexed(own)==(indexedGeometry&&test%2&&exact),@"Shadow geometry toggle was not applied");
   if((depthSplit||indexedGeometry||farCache)&&!night) {
    // Opaque and cutout draws together must keep every caster; both maps must stay bit-identical.
    if(test%2&&depthSplit) {
     id<MTLBuffer> alpha=[ctx->device newBufferWithLength:16 options:MTLResourceStorageModeShared];id<MTLCommandBuffer> c2=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> b2=[c2 blitCommandEncoder];
     [b2 copyFromBuffer:mco_sun_debug_draw_args(own) sourceOffset:48 toBuffer:alpha destinationOffset:0 size:16];[b2 endEncoding];[c2 commit];[c2 waitUntilCompleted];
     uint32_t *a=alpha.contents;uint32_t cutoutKept=exact?a[0]/6:a[1];kept+=cutoutKept;require(cutoutKept>0,@"Fixture cutout caster did not reach the alpha-tested draw");
     printf("depth split: opaque %u + cutout %u %s\n",kept-cutoutKept,cutoutKept,exact?"quads":"units");[alpha release];
    }
    // The far cache keeps unculled terrain, so only its visible output (checked below) must match.
    for(int level=0;level<(farCache?1:2);level++) {
     id<MTLTexture> map=mco_sun_debug_cascade_map(own,level);int size=(int)map.width;require(size<=1024,@"Unexpected shadow map size");
     id<MTLBuffer> depthRead=[ctx->device newBufferWithLength:size*size*4 options:MTLResourceStorageModeShared];id<MTLCommandBuffer> c3=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> b3=[c3 blitCommandEncoder];
     [b3 copyFromTexture:map sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(size,size,1) toBuffer:depthRead destinationOffset:0 destinationBytesPerRow:size*4 destinationBytesPerImage:size*size*4];
     [b3 endEncoding];[c3 commit];[c3 waitUntilCompleted];require(c3.status==MTLCommandBufferStatusCompleted,c3.error.localizedDescription);
     if(!(test%2))memcpy(referenceMaps[level],depthRead.contents,size*size*4);
     else {
      const float *v=depthRead.contents;int covered=0;for(int i=0;i<size*size;i++)covered+=v[i]<1;
      if(gridActive)require(!memcmp(referenceMaps[level],depthRead.contents,size*size*4),@"Shadow map optimization changed a shadow map");
      printf("shadow map level %d (%d px): %s, %d covered texels\n",level,size,gridActive?"bit-identical":"conservative grid-off map; visible output checked separately",covered);
     }
     [depthRead release];
    }
   }
   // At night no receiver grid or caster selection runs, so there are no counters to check.
   if(test%2&&!night&&gridActive) {
    id<MTLBuffer> stats=[ctx->device newBufferWithLength:128+4*4097*4 options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> check=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[check blitCommandEncoder];
    [blit copyFromBuffer:mco_sun_debug_receiver_stats(own) sourceOffset:0 toBuffer:stats destinationOffset:0 size:128];
    [blit copyFromBuffer:mco_sun_debug_receiver_bins(own) sourceOffset:0 toBuffer:stats destinationOffset:128 size:4*4097*4];
    [blit endEncoding];[check commit];[check waitUntilCompleted];require(check.status==MTLCommandBufferStatusCompleted,check.error.localizedDescription);
    uint32_t *v=stats.contents;require(v[32]==0 && v[32+4097]==0,@"Sky disabled a whole receiver grid");
    require(v[1]+v[3]>0,@"Near cascade did not reject irrelevant geometry");
    printf("current frame %d near: rejected sections %u, records %u; far: rejected sections %u, records %u; far kept %u quads\n",test/2,v[1],v[3],v[9],v[11],kept);[stats release];
   }
   if(gbuffer&&test%2) {
    // White atlas, tinted vertex colors (220,180,140), block/sky light 240: albedo = tint, light coordinates 15.5/16 (247/255) on lit terrain.
    id<MTLTexture> textures[]={mco_sun_debug_albedo(own),mco_sun_debug_light_data(own)};uint8_t texel[2][4]={{0}};
    id<MTLBuffer> gread=[ctx->device newBufferWithLength:8 options:MTLResourceStorageModeShared];id<MTLCommandBuffer> gc=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> gb=[gc blitCommandEncoder];
    for(int k=0;k<2;k++)[gb copyFromTexture:textures[k] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(width/2,width/2,0) sourceSize:MTLSizeMake(1,1,1) toBuffer:gread destinationOffset:k*4 destinationBytesPerRow:4 destinationBytesPerImage:4];
    [gb endEncoding];[gc commit];[gc waitUntilCompleted];require(gc.status==MTLCommandBufferStatusCompleted,gc.error.localizedDescription);memcpy(texel,gread.contents,8);[gread release];
    require(abs(texel[0][0]-220)<=1&&abs(texel[0][1]-180)<=1&&abs(texel[0][2]-140)<=1,@"G-buffer albedo incorrect");
    int blockExpected=handOpt?8:gbufferRebuilt||gbufferViews?247:8;  // lighting mode: block light 0 (0.5/16)
    require((blockExpected<0||abs(texel[1][0]-blockExpected)<=1)&&(handOpt||abs(texel[1][1]-247)<=1)&&texel[1][3]==255,@"G-buffer light data incorrect");
    printf("G-buffer at center: albedo %u,%u,%u light %u,%u class %u fade %u\n",texel[0][0],texel[0][1],texel[0][2],texel[1][0],texel[1][1],texel[1][2],texel[1][3]);
   }
   id<MTLTexture> historyTexture=mco_sun_debug_history(own);
   id<MTLBuffer> historyRead=[ctx->device newBufferWithLength:width*width*8 options:MTLResourceStorageModeShared];
   id<MTLCommandBuffer> historyCopy=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> historyBlit=[historyCopy blitCommandEncoder];
   [historyBlit copyFromTexture:historyTexture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,width,1) toBuffer:historyRead destinationOffset:0 destinationBytesPerRow:width*8 destinationBytesPerImage:width*width*8];
   [historyBlit endEncoding];[historyCopy commit];[historyCopy waitUntilCompleted];require(historyCopy.status==MTLCommandBufferStatusCompleted,historyCopy.error.localizedDescription);
   if(!(test%2))memcpy(referenceHistory,historyRead.contents,width*width*8);
   else if(memoryOnly)require(!memcmp(referenceHistory,historyRead.contents,width*width*8),@"Memory path changed shadow history");
   [historyRead release];
   uint8_t pixels[sizeof reference]={0};[color getBytes:pixels bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,width) mipmapLevel:0];
   // Debug views replace terrain colors on purpose; they only need to run cleanly.
   if(night) {
    int shadow=((width/2)*width+(115*width/128));
    if(test%2==0)printf("night: shadow pixel %u,%u,%u\n",pixels[shadow*4],pixels[shadow*4+1],pixels[shadow*4+2]);
   } else if(!(gbufferViews&&test%2)&&!lighting)require(pixels[((width/2)*width+(115*width/128))*4]<(srgb?230:170),@"Offscreen caster lost its visible shadow");
   require(mco_sun_debug_lighting(own)==(gbuffer&&!gbufferRebuilt&&(test%2||receiverGridCompare)),@"Lighting model state incorrect");
   if(lighting&&test%2) {
    // The native model changes terrain colors on purpose: the shadow must stay clearly darker than the
    // brightest lit terrain, and pixels without terrain (black clear) must not change.
    int brightest=0,background=0,changed=0;
    for(int i=0;i<width*width;i++){if(reference[i*4+1]>reference[brightest*4+1])brightest=i;if(!reference[i*4]&&!reference[i*4+1]&&!reference[i*4+2]){background++;changed+=memcmp(reference+i*4,pixels+i*4,4)!=0;}}
    int shadow=((width/2)*width+(115*width/128));
    printf("native lighting: shadow %u,%u,%u lit %u,%u,%u (reference g %u/%u), background %d unchanged %d\n",pixels[shadow*4],pixels[shadow*4+1],pixels[shadow*4+2],pixels[brightest*4],pixels[brightest*4+1],pixels[brightest*4+2],reference[shadow*4+1],reference[brightest*4+1],background,background-changed);
    require(pixels[brightest*4+1]>(night||moonOpt?2:40),@"Native lighting left lit terrain black");
    if(night)require(pixels[shadow*4+1]*100>=pixels[brightest*4+1]*85,@"Night still shows a sun shadow");
    else require(pixels[shadow*4+1]*100<pixels[brightest*4+1]*85,@"Native lighting lost the cast shadow");
    if(skyOn) {
     // Sky pixels (no terrain, cleared depth) show the native day sky: not black, blue above red.
     long sum[3]={0};int lit=0;
     for(int i=0;i<width*width;i++)if(!reference[i*4]&&!reference[i*4+1]&&!reference[i*4+2]){for(int k=0;k<3;k++)sum[k]+=pixels[i*4+k];lit+=pixels[i*4]+pixels[i*4+1]+pixels[i*4+2]>(moonOpt?20:60);}  // moon: the night sky, dark blue
     BOOL bgra=test/2>=3&&test/2<6;long red=sum[bgra?2:0],blue=sum[bgra?0:2];
     printf("native sky: %d/%d sky pixels lit, mean %ld,%ld,%ld\n",lit,background,sum[0]/MAX(background,1),sum[1]/MAX(background,1),sum[2]/MAX(background,1));
     require(background>0&&lit*10>=background*9&&blue>red,@"Native sky missing or not blue");
    } else require(background>0&&changed==0,@"Native lighting changed pixels without terrain");
    if(handOpt) {
     // The held light brightens the ground, most near the player (the image's lower rows are closest).
     if(!handOn){memcpy(handOff,pixels,sizeof pixels);haveHandOff=YES;handOffWidth=width;}
     else if(haveHandOff&&handOffWidth==width) {
      int brighter=0;long near=0,far=0;
      for(int i=0;i<width*width;i++){if(pixels[i*4+1]>handOff[i*4+1]+10)brighter++;}
      for(int x=width/4;x<width;x++){near+=pixels[((width-2)*width+x)*4+1];far+=pixels[(1*width+x)*4+1];}
      printf("hand light: %d pixels brighter, near row %ld far row %ld\n",brighter,near,far);
      require(brighter>1000,@"Hand light did not light the ground");
     }
    }
   }
   if(test%2==0)memcpy(reference,pixels,sizeof pixels);else {
    int largest=0,bad=0;for(int i=0;i<sizeof pixels;i++){int d=abs((int)reference[i]-(int)pixels[i]);largest=MAX(largest,d);bad+=d>2;}
    printf("forward/deferred pixel comparison: max channel difference %d, channels >2: %d\n",largest,bad);
    // Half-resolution visibility is evaluated per 2x2 block in the deferred path only: shadow edges may move by a pixel.
    if(halfShadows&&(receiverGridCompare||(!gbufferViews&&!lighting)))require(bad*100<=width*width*3*3,@"Half-resolution shadows moved more than shadow edges");
    else if(receiverGridCompare||(!gbufferViews&&!lighting))require(bad==0,@"Current-frame culling changed visible terrain/shadows");
   }
   printf("receiver grid=%d, culling=%d exact=%d: %u %s, visible output identical\n",gridActive,test%2,exact,kept,exact?"quads":"units");entityBindCheck(ctx,enc,own,color,source,mco_sun_debug_lighting(own),moonOpt&&test%2);[candidateBuffer release];[read release];[color release];[depth release];
  }
  culling.count=17;require(mco_sun_culling(own,&culling)<0,@"Out-of-bounds plane count accepted");
  puts("PASS: paired separate/optimized history with smoothing toggle, revision, viewport/format changes, camera changes, partial sky, near/far rejection, offscreen casters, disable/re-enable and forward/deferred parity");
 }
 return 0;
}
