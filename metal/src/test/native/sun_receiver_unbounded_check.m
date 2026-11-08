// Culling must reduce indirect geometry while retaining a caster outside the camera view whose shadow is visible.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern void mco_sun_debug_force_grouped_cull(void *,int);
extern void mco_sun_selection_optimization(void *,int);
extern int mco_sun_debug_grouped_cull(void *);
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_candidates(void *,id<MTLBuffer>,int);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern int mco_sun_debug_deferred(void *);
extern id<MTLBuffer> mco_sun_debug_receiver_stats(void *);
extern id<MTLBuffer> mco_sun_debug_receiver_bins(void *);
extern int mco_sun_culling(void *,const void *);
extern id<MTLBuffer> mco_sun_debug_draw_args(void *);
extern int mco_sun_debug_exact(void *);
extern void mco_sun_debug_force_legacy(void *,int);
typedef struct { uint32_t count;float nearRadius;uint32_t pad[2];simd_float4 planes[16]; } Cull;
static uint32_t bound(int x,int y,int z) { return (x+16)|((y+16)<<8)|((z+16)<<16); }
extern void mco_sun_debug_force_stats(void *,int);
int main(int argc,const char **argv) {
 @autoreleasepool {
  require(argc==3,@"Usage: sun_culling_check TERRAIN SUN");
  NSString *source=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
  source=[source stringByAppendingString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:nil]];
  char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
  void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,[NSString stringWithUTF8String:error]);mco_sun_debug_force_stats(own,1);mco_sun_debug_force_grouped_cull(own,1);Enc *enc=mc_enc_new(ctx);
  // Exercise partial and full records. Only the last cutout quad is opaque,
  // so dropping a tail entry loses the offscreen caster's visible shadow.
  const uint32_t counts[]={1,17,32,64};uint32_t starts[]={0,1,18,50};
  Vertex vertices[114*4]={0};
  for(int q=0;q<4;q++)for(uint32_t local=0;local<counts[q];local++)for(int c=0;c<4;c++) {
   float radius=q?.4:4,cx=q==1?1:q==2?6.5:0,cz=q==2?-3.5:1.5;
   Vertex *v=&vertices[(starts[q]+local)*4+c];v->p[0]=8+cx+((c==1||c==2)?radius:-radius);v->p[1]=q?2:0;
   v->p[2]=q==0?8+((c>=2)?radius:-radius):8+cz+((c>=2)?radius:-radius);
   memset(v->color,255,4);if(q==1 && local+1<counts[q])v->color[3]=0;
   v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=.5;
  }
  Section section[2]={{.x=-8,.z=-8,.count=3},{.x=-8,.z=-24,.start=3,.count=1}};
  Record records[4]={{.lo=bound(4,0,4),.hi=bound(12,0,12)},
   {.start=1,.meta=16|(1u<<9),.lo=bound(8,2,9),.hi=bound(10,2,10)},
   {.start=18,.meta=31,.lo=bound(14,2,4),.hi=bound(15,2,5)},
   {.start=50,.slot=1,.meta=63,.lo=bound(7,2,9),.hi=bound(9,2,10)}};
  uint32_t lists[]={0,1,2,3},args[]={384,4,0,0,0};
  id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,section,sizeof section),recs=buffer(ctx,records,sizeof records),list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
  // Visible ground x=-5..0,z=.5..2.5. The caster at x=1 lies outside it, but its shadow reaches x=-.5.
  simd_float4x4 projection={.columns={{.4,0,0,0},{0,0,.1,0},{0,1,0,0},{1,-1.5,1.2,1}}};
  uint8_t globals[48]={0},terrain[80]={0};int camera[]={0,8,0};memcpy(globals,camera,12);simd_float4x4 identity=matrix_identity_float4x4;memcpy(terrain,&identity,64);int ts[]={4,4};memcpy(terrain+64,ts,8);
  float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};
  id<MTLBuffer> p=buffer(ctx,&projection,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
  int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
  id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
  Sun sun={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},.cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0/1024,.00015,1},.sections=2,.resolution=1024,.on=1};
  // Sweeping the visible volume toward (.6,.8,0) removes the right plane and keeps these three planes.
  Cull culling={0};
  uint8_t reference[128*128*4]={0};
  // No per-iteration caller pool: reproduce the actual Java FFM lifetime.
  for(int test=0;test<6;test++) {
            testProfileBegin(enc);
   BOOL legacy=NO;mco_sun_debug_force_legacy(own,legacy);
   Cull off={0};require(mco_sun_culling(own,test%2?&culling:&off)==0,@"Cull packet rejected");
   MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
   td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];id<MTLTexture> target[]={color};float clear[]={1,0,0,0,1};mc_render_begin(enc,1,target,clear,depth,1,0,128,128);
   // Every pair changes the camera. A sky strip remains uncovered on the left.
   simd_float4x4 view=projection;view.columns[3].x+=.01f*(test/2);memcpy(p.contents,&view,64);
   OwnSunSurfaceView surface={.clip=view,.camera={.025*(test/2),8,0},.enabled=test%2};
   require(mco_sun_surface(own,&surface)==0,@"Surface packet rejected");
   uint32_t candidates[]={0,1};id<MTLBuffer> candidateBuffer=buffer(ctx,candidates,sizeof candidates);
   require(mco_sun_candidates(own,test%2?candidateBuffer:nil,test%2?2:-1)==0,@"Candidate packet rejected");
   uint8_t packet[400]={0};memcpy(packet,&sun,144);memcpy(packet+144,&view,64);
   memcpy(packet+208,surface.camera,24);*(float *)(packet+240)=1.0f/60;
   Sun far=sun;far.resolution=512;far.config.x=10;far.config.y=1.0f/512;
   for(int c=0;c<4;c++){far.clip.columns[c].x*=.5;far.clip.columns[c].y*=.5;}memcpy(packet+256,&far,144);
   require(mco_sun_frame(own,enc,packet,400,arena,sections,recs,atlas,sampler,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
   require(mco_sun_debug_deferred(own)==test%2,@"Current-frame deferred path did not activate immediately");
   uint8_t df[80]={0};*(int *)(df+4)=1;require(mco_draw(own,enc,0,df,80,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
   require(mco_sun_finish(own,enc,error,sizeof error)==test%2,[NSString stringWithUTF8String:error]);
   require(!mco_sun_debug_deferred(own),@"Translucent draws would retain deferred state");
   id<MTLCommandBuffer> cmd=testProfileCommit(enc,ctx);[cmd waitUntilCompleted];require(cmd.status!=MTLCommandBufferStatusError,cmd.error.localizedDescription);[cmd release];
   id<MTLBuffer> read=[ctx->device newBufferWithLength:16 options:MTLResourceStorageModeShared];cmd=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[cmd blitCommandEncoder];[blit copyFromBuffer:mco_sun_debug_draw_args(own) sourceOffset:0 toBuffer:read destinationOffset:0 size:16];[blit endEncoding];[cmd commit];[cmd waitUntilCompleted];require(cmd.status!=MTLCommandBufferStatusError,cmd.error.localizedDescription);
   BOOL exact=mco_sun_debug_exact(own);require(exact!=legacy,@"Exact geometry/fallback path was not selected");
   uint32_t *arguments=read.contents,kept=exact?arguments[0]/6:arguments[1];
   if(test%2) {
    id<MTLBuffer> stats=[ctx->device newBufferWithLength:128+4*4097*4 options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> check=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[check blitCommandEncoder];
    [blit copyFromBuffer:mco_sun_debug_receiver_stats(own) sourceOffset:0 toBuffer:stats destinationOffset:0 size:128];
    [blit copyFromBuffer:mco_sun_debug_receiver_bins(own) sourceOffset:0 toBuffer:stats destinationOffset:128 size:4*4097*4];
    [blit endEncoding];[check commit];[check waitUntilCompleted];require(check.status==MTLCommandBufferStatusCompleted,check.error.localizedDescription);
    uint32_t *v=stats.contents;require(v[32]==0 && v[32+4097]==0,@"Sky disabled a whole receiver grid");
    require(v[1]+v[3]>0,@"Near cascade did not reject irrelevant geometry");
    printf("current frame %d near: rejected sections %u, records %u; far: rejected sections %u, records %u; far kept %u quads\n",test/2,v[1],v[3],v[9],v[11],kept);[stats release];
   }
   uint8_t pixels[sizeof reference];[color getBytes:pixels bytesPerRow:128*4 fromRegion:MTLRegionMake2D(0,0,128,128) mipmapLevel:0];
   require(pixels[(64*128+115)*4]<170,@"Offscreen caster lost its visible shadow");
   if(test%2==0)memcpy(reference,pixels,sizeof pixels);else {
    int largest=0,bad=0;for(int i=0;i<sizeof pixels;i++){int d=abs((int)reference[i]-(int)pixels[i]);largest=MAX(largest,d);bad+=d>2;}
    printf("forward/deferred pixel comparison: max channel difference %d, channels >2: %d\n",largest,bad);
    require(bad==0,@"Current-frame culling changed visible terrain/shadows");
   }
   printf("culling=%d exact=%d: %u %s, visible output identical\n",test%2,exact,kept,exact?"quads":"units");[candidateBuffer release];[read release];[color release];[depth release];
  }
  culling.count=17;require(mco_sun_culling(own,&culling)<0,@"Out-of-bounds plane count accepted");
  puts("PASS: zero coarse planes retain independent current-frame culling during camera changes, partial sky, near/far rejection, offscreen casters, disable/re-enable and forward/deferred parity");
 }
 return 0;
}
