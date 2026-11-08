// Film grain in the real camera composite on offscreen targets. No Minecraft window.
// Usage: grain_check TERRAIN SUN CAMERA
#include "sun_test_common.h"
extern int mco_camera_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern void mco_camera_reset(void *);
extern void mco_camera_debug_stats(void *,uint64_t *);
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor,p[16],grain;} Camera;
_Static_assert(sizeof(Camera)==448,"Camera packet with film grain");
static Camera packet(int width,int height,float on,float strength,float size,float time){
 Camera f={.inverse=matrix_identity_float4x4,.previous=matrix_identity_float4x4,.screen={width,height,1.f/60,0},.lightColor={1,.9,.75,time}};
 f.p[0].x=1;f.grain=(simd_float4){on,strength,size,0};return f;
}
// Left to right: black to white. Rows are identical, so every column has one input value.
static id<MTLTexture> gradient(Ctx *ctx,MTLPixelFormat format,int width,int height){
 MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
 td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture> t=[ctx->device newTextureWithDescriptor:td];
 uint8_t *pixels=malloc(width*height*4);for(int y=0;y<height;y++)for(int x=0;x<width;x++){uint8_t v=(uint8_t)(x*255/(width-1));uint8_t *p=pixels+(y*width+x)*4;p[0]=p[1]=p[2]=v;p[3]=102;}
 [t replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:pixels bytesPerRow:width*4];free(pixels);return t;
}
static id<MTLTexture> depthTarget(Ctx *ctx,int width,int height){
 MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:width height:height mipmapped:NO];
 td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;return [ctx->device newTextureWithDescriptor:td];
}
static double run(void *own,Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,Camera *f,int length,int expected,uint8_t *out){
 char error[4096]={0};int result=mco_camera_post(own,enc,color,depth,f,length,error,sizeof error);
 require(result==expected,[NSString stringWithFormat:@"camera post returned %d, expected %d: %s",result,expected,error]);
 id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
 if(out)[color getBytes:out bytesPerRow:color.width*4 fromRegion:MTLRegionMake2D(0,0,color.width,color.height) mipmapLevel:0];
 return (command.GPUEndTime-command.GPUStartTime)*1000;
}
// Mean and standard deviation of (output - input) over a column range, in 8-bit levels.
static void stats(const uint8_t *out,int width,int height,int x0,int x1,double *mean,double *deviation){
 double sum=0,sq=0,n=0;
 for(int y=0;y<height;y++)for(int x=x0;x<x1;x++){double d=(double)out[(y*width+x)*4]-(double)(x*255/(width-1));sum+=d;sq+=d*d;n++;}
 *mean=sum/n;*deviation=sqrt(fmax(0,sq/n-(*mean)*(*mean)));
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==4,@"Usage: grain_check TERRAIN SUN CAMERA");
 NSString *source=@"";for(int i=1;i<=3;i++){NSString *part=[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:nil];require(part!=nil,@"Shader source missing");source=[source stringByAppendingString:part];}
 char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));
 void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));Enc *enc=mc_enc_new(ctx);
 const int width=512,height=384;uint64_t s[6];
 MTLPixelFormat formats[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatRGBA8Unorm_sRGB};
 for(int fi=0;fi<2;fi++){
  uint8_t *a=malloc(width*height*4),*b=malloc(width*height*4),*c=malloc(width*height*4);
  id<MTLTexture> depth=depthTarget(ctx,width,height);
  // Off: a camera packet without film grain (old 432-byte layout) does no work.
  id<MTLTexture> color=gradient(ctx,formats[fi],width,height);Camera off=packet(width,height,0,0,0,0);
  run(own,enc,color,depth,&off,432,0,NULL);mco_camera_debug_stats(own,s);require(s[0]==0,@"Disabled film grain still drew a pass");
  // On: one pass through framebuffer fetch, no image copy.
  Camera on=packet(width,height,1,.5f,1.5f,1.0f);
  color=gradient(ctx,formats[fi],width,height);run(own,enc,color,depth,&on,sizeof on,1,a);mco_camera_debug_stats(own,s);
  require(s[0]==1&&s[1]==0&&s[2]==0&&s[4]==1,[NSString stringWithFormat:@"Film grain alone: passes %llu colorCopies %llu depthCopies %llu fetch %llu",s[0],s[1],s[2],s[4]]);
  double darkMean,darkDev,midMean,midDev,brightMean,brightDev;
  stats(a,width,height,8,width/16,&darkMean,&darkDev);stats(a,width,height,width*7/16,width*9/16,&midMean,&midDev);stats(a,width,height,width*15/16,width-8,&brightMean,&brightDev);
  printf("%s: grain std dark %.2f mid %.2f bright %.2f levels; mean shift dark %.2f mid %.2f bright %.2f\n",fi?"sRGB target":"unorm target",darkDev,midDev,brightDev,darkMean,midMean,brightMean);
  require(midDev>4&&midDev<15,@"Midtone grain strength outside the expected range");
  require(fabs(midMean)<1.5,@"Film grain shifted midtone brightness");
  require(darkDev<midDev&&brightDev<midDev,@"Grain is not strongest in midtones");
  for(int i=0;i<width*height;i++)require(a[i*4+3]==102,@"Film grain changed world alpha");
  // Same moment: same pattern. Another 24 fps step: a new pattern.
  color=gradient(ctx,formats[fi],width,height);run(own,enc,color,depth,&on,sizeof on,1,b);
  require(memcmp(a,b,width*height*4)==0,@"Film grain is not deterministic for one moment");
  Camera later=packet(width,height,1,.5f,1.5f,1.0f+1.0f/24);color=gradient(ctx,formats[fi],width,height);run(own,enc,color,depth,&later,sizeof later,1,c);
  int changed=0;for(int i=0;i<width*height;i++)changed+=a[i*4]!=c[i*4];require(changed>width*height/3,@"Film grain did not move between frames");
  mco_camera_reset(own);free(a);free(b);free(c);
 }
 // Larger grain at 1080 lines: neighbouring pixels agree more.
 {
  int w=1920,h=1080;uint8_t *fineOut=malloc(w*h*4),*coarseOut=malloc(w*h*4);id<MTLTexture> depth=depthTarget(ctx,w,h);
  Camera fine=packet(w,h,1,.5f,1.0f,1.0f),coarse=packet(w,h,1,.5f,4.0f,1.0f);
  run(own,enc,gradient(ctx,MTLPixelFormatRGBA8Unorm,w,h),depth,&fine,sizeof fine,1,fineOut);run(own,enc,gradient(ctx,MTLPixelFormatRGBA8Unorm,w,h),depth,&coarse,sizeof coarse,1,coarseOut);
  double a=0,b=0;for(int y=h/4;y<h*3/4;y++)for(int x=w*7/16;x<w*9/16;x++){int i=(y*w+x)*4;a+=abs((int)fineOut[i]-(int)fineOut[i+4]);b+=abs((int)coarseOut[i]-(int)coarseOut[i+4]);}
  printf("neighbour difference at 1080 lines: size 1 %.2f, size 4 %.2f levels\n",a/(h/2*w/8),b/(h/2*w/8));
  require(b<a*.6,@"Grain size did not make the grain coarser");free(fineOut);free(coarseOut);mco_camera_reset(own);
 }
 // Cost at 1920x1080 and 3024x1890 (MacBook Pro Retina): film grain alone, median of 30 frames.
 int sizes[2][2]={{1920,1080},{3024,1890}};
 for(int k=0;k<2;k++){
  int w=sizes[k][0],h=sizes[k][1];id<MTLTexture> color=gradient(ctx,MTLPixelFormatRGBA8Unorm,w,h),depth=depthTarget(ctx,w,h);double times[30];
  // Alone it needs its own pass; with any other camera effect it only adds arithmetic to the composite that runs anyway.
  const char *names[]={"film grain alone","vignette alone","vignette + film grain"};double medians[3];
  for(int v=0;v<3;v++){
   Camera on=packet(w,h,v!=1,.5f,1.5f,0);if(v>0){on.p[9].x=1;on.p[9].y=.3f;on.p[9].z=.75f;on.p[9].w=.4f;on.p[10].x=1;}
   for(int i=0;i<30;i++){on.lightColor.w=i/60.f;times[i]=run(own,enc,color,depth,&on,sizeof on,1,NULL);}
   for(int i=0;i<30;i++)for(int j=i+1;j<30;j++)if(times[j]<times[i]){double t=times[i];times[i]=times[j];times[j]=t;}
   medians[v]=times[15];printf("%s at %dx%d: %.3f ms GPU per frame (median of 30)\n",names[v],w,h,medians[v]);
  }
  printf("film grain added to an existing camera pass at %dx%d: %+.3f ms\n",w,h,medians[2]-medians[1]);mco_camera_reset(own);
 }
 printf("PASS: film grain off costs nothing; on is one framebuffer-fetch pass without copies; midtone-weighted, brightness-neutral, deterministic per 24 fps step, moving between steps, size-controlled, alpha kept, unorm and sRGB targets\n");
 return 0;
}}
