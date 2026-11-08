// An east-facing wall cannot receive direct light from a setting sun behind it.
// Exercise actual forward/deferred terrain, temporal history, and both resolve variants.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_debug_deferred(void *);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern void mco_sun_filter_optimization(void *,int);
extern void mco_sun_memory_optimization(void *,int);

int main(int argc,const char **argv) {
 @autoreleasepool {
  require(argc==3,@"Usage: sun_backface_check TERRAIN SUN");
  NSString *source=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
  source=[source stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
  char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);
  require(ctx!=NULL,[NSString stringWithUTF8String:error]);
  void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);
  require(own!=NULL,[NSString stringWithUTF8String:error]);Enc *enc=mc_enc_new(ctx);
  Vertex vertices[4]={0};
  for(int c=0;c<4;c++) {
   Vertex *v=&vertices[c];v->p[0]=-4;v->p[1]=(c==1||c==2)?2:-2;v->p[2]=c>=2?-2:2;
   memset(v->color,255,4);v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=.5;
  }
  Section section={.count=1};Record record={0};uint32_t index=0,args[]={384,1,0,0,0};
  id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),records=buffer(ctx,&record,sizeof record);
  id<MTLBuffer> list=buffer(ctx,&index,4),draw=buffer(ctx,args,sizeof args);
  simd_float4x4 view={.columns={{0,0,-.05,0},{0,.4,0,0},{.4,0,0,0},{0,0,.1,1}}};
  uint8_t globals[48]={0},terrain[80]={0};simd_float4x4 identity=matrix_identity_float4x4;memcpy(terrain,&identity,64);
  int size[]={4,4};memcpy(terrain+64,size,8);float fog[]={0,0,0,0,1000,2000,1000,2000,1000,1000};
  id<MTLBuffer> p=buffer(ctx,&view,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
  int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
  id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];
  sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
  int checks=0;
  // Modes: forward, forward with temporal resolve, deferred with/without smoothing.
  for(int mode=0;mode<4;mode++)for(int filter=0;filter<2;filter++)for(int memory=0;memory<2;memory++) {
   mco_sun_filter_optimization(own,filter);mco_sun_memory_optimization(own,memory);
   for(int frame=0;frame<12;frame++) {
    BOOL back=frame%4>=2;float sy=frame<4?.10:.04,sx=(back?-1:1)*sqrtf(1-sy*sy);
    // Last two frames are outside the shadow-map volume and distance fade.
    float distance=frame>=6&&frame<8?64:4;for(int c=0;c<4;c++)vertices[c].p[0]=-distance;
    memcpy(arena.contents,vertices,sizeof vertices);view.columns[0].z=.2f/-distance;memcpy(p.contents,&view,64);
    Sun sun={.clip={.columns={{.2f*sy,0,-.025f*sx,0},{-.2f*sx,0,-.025f*sy,0},{0,.2,0,0},{0,0,.5,1}}},
     .sun={sx,sy,0,1},.config={5,1.0/512,.00015,1},.sections=1,.resolution=512,.on=1};
    if(frame>=8){sun.resolution=0;sun.config.w=0;}
    BOOL temporal=mode!=0,deferred=mode>=2,smooth=mode==1||mode==3;
    OwnSunSurfaceView surface={.clip=view,.enabled=deferred};require(mco_sun_surface(own,&surface)==0,@"Surface rejected");
    uint8_t packet[400]={0};memcpy(packet,&sun,144);memcpy(packet+144,&view,64);
    *(float *)(packet+240)=1.0f/60;*(uint32_t *)(packet+244)=checks;*(uint32_t *)(packet+248)=smooth;
    Sun far=sun;far.config.x=10;memcpy(packet+256,&far,144);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:64 mipmapped:NO];
    td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
    td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage|=MTLTextureUsageShaderRead;
    id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td],targets[]={color};float clear[]={1,0,0,0,1};
    mc_render_begin(enc,1,targets,clear,depth,1,0,64,64);
    require(mco_sun_frame(own,enc,packet,temporal?400:144,arena,sections,records,atlas,sampler,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
    require(mco_sun_debug_deferred(own)==(deferred&&sun.resolution>0),@"Requested deferred/forward path was not exercised");
    uint8_t df[80]={0};*(int *)(df+4)=1;
    require(mco_draw(own,enc,0,df,80,arena,sections,records,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
    if(temporal&&sun.resolution)require(mco_sun_finish(own,enc,error,sizeof error)==1,[NSString stringWithUTF8String:error]);
    id<MTLCommandBuffer> cmd=mc_enc_commit(enc);[cmd waitUntilCompleted];require(cmd.status!=MTLCommandBufferStatusError,cmd.error.localizedDescription);[cmd release];
    uint8_t pixel[4];[color getBytes:pixel bytesPerRow:4 fromRegion:MTLRegionMake2D(32,32,1,1) mipmapLevel:0];
    printf("mode=%d filter=%d memory=%d frame=%d back=%d distance=%.0f intensity=%u\n",mode,filter,memory,frame,back,distance,pixel[0]);fflush(stdout);
    require(back?(pixel[0]>=89&&pixel[0]<=95):pixel[0]>=245,back?@"Sun-away wall receives impossible direct sunlight":@"Sun-facing wall lost valid direct sunlight");
    checks++;[color release];[depth release];
   }
  }
  printf("PASS: %d low-sun wall checks, forward/deferred, history transitions, filter/memory toggles and beyond shadow range and resolution OFF on %s\n",checks,name);
 }
 return 0;
}
