// Paired real camera post timing, with the immutable pre-optimization shader. No game/FPS claim.
#include "sun_test_common.h"
#include "godrays_reference.h"
extern int mco_camera_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern int mco_camera_debug_rays_reference(void *,int);
extern void mco_camera_debug_stats(void *,uint64_t *);
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor,p[16];} Camera;
static void set(Camera *f,int i,float v){f->p[i/4][i%4]=v;}
static Camera settings(int w,int h,int samples,float scale){
 Camera f={.screen={w,h,1.f/60,0}};float values[]={1,0,.2,.7,.2,1,3,.5,0,1,8,4,8,1,1,12,6,0,1,8,32,12,0,.3,5,.4,.08,.2,.2,8,1,0,0,1,.2,2,0,.3,.75,.4,1,0,0,0,0,.3,1,.1,1,.35,.015,64,16,.25,.65,.3,1};
 for(int i=0;i<57;i++)set(&f,i,values[i]);set(&f,52,samples);set(&f,53,scale);set(&f,57,1);
 float c=1/tanf(1.2f*.5f);simd_float4x4 p={.columns={{c/((float)w/h),0,0,0},{0,c,0,0},{0,0,0,-1},{0,0,.05,0}}};f.inverse=simd_inverse(p);f.previous=p;f.p[15]=(simd_float4){0,0,-1,0};return f;
}
static double median(double *values,int n){double copy[128];memcpy(copy,values,n*sizeof(double));for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(copy[j]<copy[i]){double t=copy[i];copy[i]=copy[j];copy[j]=t;}return copy[n/2];}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==5,@"Usage: godrays_cost_check TERRAIN SUN CAMERA REFERENCE");NSString *source=@"";for(int i=1;i<=3;i++)source=[source stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:nil]];source=[source stringByAppendingString:godraysReference(@(argv[4]))];
 char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));void *renderers[2]={NULL,NULL};for(int k=0;k<2;k++){renderers[k]=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(renderers[k]!=NULL,@(error));require(mco_camera_debug_rays_reference(renderers[k],k)==0,@"Reference unavailable");}Enc *enc=mc_enc_new(ctx);
 printf("GPU %s\n",name);id<MTLTexture> atlas=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
 Vertex vertices[12]={0};for(int q=0;q<3;q++)for(int c=0;c<4;c++){Vertex *v=&vertices[q*4+c];v->p[0]=q*4-6+(c==1||c==2?2:0);v->p[1]=6;v->p[2]=c>=2?-64:4;memset(v->color,255,4);v->uv[0]=v->uv[1]=.5;}
 Section section={.count=3};Record records[3]={{.meta=6u<<6},{.start=1,.meta=6u<<6},{.start=2,.meta=6u<<6}};
 id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records);
 uint8_t packet[688]={0};for(int i=0;i<3;i++){float range=i==0?16:i==1?32:64;Sun s={.clip={.columns={{1/range,0,0,0},{0,0,-1.f/32,0},{0,1/range,0,0},{0,0,.5,1}}},.sun={0,1,0,1},.config={range,1.f/512,.00015,1},.sections=1,.resolution=512,.on=1};memcpy(packet+(i?256+(i-1)*144:0),&s,144);}*(uint32_t *)(packet+252)=3;
 for(int scenario=0;scenario<11;scenario++){@autoreleasepool{
  BOOL large=(scenario>=3&&scenario<6)||scenario==8||scenario==10,sky=scenario>=9;
  int w=large?2560:1920,h=large?1440:1080,samples=(scenario<6?scenario%3:scenario==8?2:0)==0?16:(scenario<6?scenario%3:scenario==8?2:0)==1?32:64;
  float scale=samples==64?.5:.25;int levels=scenario==6?2:scenario==7||scenario==8?4:3;
  memset(packet,0,sizeof packet);for(int i=0;i<levels;i++){float range=i==0?16:i==levels-1?64:16*(i+1);Sun s={.clip={.columns={{1/range,0,0,0},{0,0,-1.f/32,0},{0,1/range,0,0},{0,0,.5,1}}},.sun={0,1,0,1},.config={range,1.f/512,.00015,1},.sections=1,.resolution=512,.on=1};memcpy(packet+(i?256+(i-1)*144:0),&s,144);}*(uint32_t *)(packet+252)=levels;
  Camera f=settings(w,h,samples,scale);
  MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
  id<MTLTexture> targets[]={color};float clear[]={1,.08,.08,.08,.4};for(int k=0;k<2;k++){mc_render_begin(enc,1,targets,clear,depth,1,sky?0:.05/24,w,h);require(mco_sun_frame(renderers[k],enc,packet,sizeof packet,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,@(error));id<MTLCommandBuffer> initial=mc_enc_commit(enc);[initial waitUntilCompleted];}
  double gpu[2][64]={0},march[2][64]={0},compose[2][64]={0};uint64_t memory[2]={0};int n[2]={0};uint8_t *reference=malloc(w*h*4),*current=malloc(w*h*4);int maxDifference=0,changed=0;
  for(int iteration=-8;iteration<64;iteration++)for(int pair=0;pair<2;pair++){@autoreleasepool{
   int kind=((iteration&1)?pair:1-pair);void *own=renderers[kind];
   require(mc_profile_begin(enc,16),@"Stage timestamps unavailable");mc_render_begin(enc,1,targets,clear,depth,1,sky?0:.05/24,w,h);
   require(mco_camera_post(own,enc,color,depth,&f,sizeof f,error,sizeof error)>0,@(error));
   char labels[16][160]={0};mc_profile_labels(enc,(char *)labels,160,16);int count=0;id<MTLCounterSampleBuffer> counters=mc_profile_end(enc,&count);id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);double ticks[64];mc_profile_read(ctx,counters,count,ticks);
   if(iteration>=0){int at=n[kind]++;gpu[kind][at]=(command.GPUEndTime-command.GPUStartTime)*1000;for(int g=0;g<count/4;g++){double duration=ticks[g*4+3]-ticks[g*4+2];if(strstr(labels[g],"volumetric"))march[kind][at]=duration/1000;else if(strstr(labels[g],"camera composite"))compose[kind][at]=duration/1000;}}
   if(iteration==0){[color getBytes:kind?reference:current bytesPerRow:w*4 fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];uint64_t stats[6];mco_camera_debug_stats(own,stats);memory[kind]=stats[5];}
  }}
  for(int i=0;i<w*h*4;i++){int d=abs((int)reference[i]-(int)current[i]);maxDifference=MAX(maxDifference,d);changed+=d>1;}
  printf("CASE %dx%d samples=%d scale=%.2f levels=%d sky=%d old_ms=%.6f new_ms=%.6f old_march=%.6f new_march=%.6f old_compose=%.6f new_compose=%.6f old_bytes=%llu new_bytes=%llu maxdiff=%d channels_gt1=%d\n",w,h,samples,scale,levels,sky,median(gpu[1],n[1]),median(gpu[0],n[0]),median(march[1],n[1]),median(march[0],n[0]),median(compose[1],n[1]),median(compose[0],n[0]),memory[1],memory[0],maxDifference,changed);fflush(stdout);
  require(maxDifference<=2&&changed==0,@"Optimization changed the paired image beyond one 8-bit step");free(reference);free(current);[color release];[depth release];
 }}puts("PASS: paired original/optimized production camera paths and GPU timings");
}return 0;}
