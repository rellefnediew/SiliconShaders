// Real SSAO pipeline: reversed-Z fixtures, formats, resource lifetime, queued changes and GPU timings.
#include "sun_test_common.h"
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
extern int mco_ssao_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern void mco_ssao_reset(void *);
extern void mco_ssao_debug_stats(void *,uint64_t *);
typedef struct {simd_float4x4 inverse;simd_float4 screen,controls,extra;} Ssao;
static Ssao frame(int w,int h){float focal=1.3f;simd_float4x4 projection={.columns={{focal/((float)w/h),0,0,0},{0,focal,0,0},{0,0,0,-1},{0,0,.05,0}}};return (Ssao){.inverse=simd_inverse(projection),.screen={w,h,0,0},.controls={1.25,.65,1,96}};}
static void png(NSString *path,uint8_t *p,int w,int h){
 uint8_t *opaque=malloc(w*h*4);memcpy(opaque,p,w*h*4);for(int i=0;i<w*h;i++)opaque[4*i+3]=255;
 CGColorSpaceRef cs=CGColorSpaceCreateDeviceRGB();CGContextRef c=CGBitmapContextCreate(opaque,w,h,8,w*4,cs,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);CGImageRef im=CGBitmapContextCreateImage(c);CGImageDestinationRef dest=CGImageDestinationCreateWithURL((CFURLRef)[NSURL fileURLWithPath:path],CFSTR("public.png"),1,NULL);CGImageDestinationAddImage(dest,im,NULL);require(CGImageDestinationFinalize(dest),@"PNG failed");CFRelease(dest);CGImageRelease(im);CGContextRelease(c);CGColorSpaceRelease(cs);free(opaque);
}
static id<MTLTexture> texture(Ctx *ctx,int w,int h,MTLPixelFormat format,BOOL readable){MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];d.storageMode=format==MTLPixelFormatDepth32Float?MTLStorageModePrivate:MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget|(readable?MTLTextureUsageShaderRead:0);return [ctx->device newTextureWithDescriptor:d];}
static void input(Enc *enc,id<MTLTexture> color,id<MTLTexture> depth,id<MTLRenderPipelineState> pipeline,id<MTLDepthStencilState> ds,Ssao f,uint32_t mode){
 id<MTLTexture> targets[]={color};float clear[]={1,0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,color.width,color.height);
 [enc->render setRenderPipelineState:pipeline];[enc->render setCullMode:MTLCullModeNone];[enc->render setDepthStencilState:ds];[enc->render setFragmentBytes:&f length:sizeof f atIndex:0];[enc->render setFragmentBytes:&mode length:4 atIndex:1];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
}
static void retire(Enc *enc){id<MTLCommandBuffer> b=mc_enc_commit(enc);[b waitUntilCompleted];require(b.status==MTLCommandBufferStatusCompleted,b.error.localizedDescription);}
static float *pixels(id<MTLTexture> color){int n=color.width*color.height*4;BOOL half=color.pixelFormat==MTLPixelFormatRGBA16Float,bgra=color.pixelFormat==MTLPixelFormatBGRA8Unorm||color.pixelFormat==MTLPixelFormatBGRA8Unorm_sRGB;void *raw=malloc(n*(half?2:1));[color getBytes:raw bytesPerRow:color.width*(half?8:4) fromRegion:MTLRegionMake2D(0,0,color.width,color.height) mipmapLevel:0];float *out=malloc(n*4);for(int i=0;i<n;i++){int at=bgra&&i%4==0?i+2:bgra&&i%4==2?i-2:i;out[i]=half?(float)((_Float16 *)raw)[at]:((uint8_t *)raw)[at]/255.f;require(isfinite(out[i]),@"Nonfinite SSAO output");}free(raw);return out;}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==5,@"Usage: ssao_check TERRAIN SUN SSAO OUTPUT");NSString *src=@"";for(int i=1;i<=3;i++)src=[src stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:nil]];
 char err[16384]={0},name[256];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));void *own=mco_new(ctx,src.UTF8String,0,0,0,err,sizeof err);require(own!=NULL,@(err));Enc *enc=mc_enc_new(ctx);
 NSString *fixture=@"\nstruct SsaoTestOut{float4 color [[color(0)]];float depth [[depth(any)]];};fragment SsaoTestOut ssao_input_fs(float4 frag [[position]],constant SsaoFrame &f [[buffer(0)]],constant uint &mode [[buffer(1)]]){float4 pp=f.inverse*float4(frag.xy/f.screen.xy*2-1,1,1);float3 ray=normalize(pp.xyz/pp.w);float t=mode==4?1e8:-4/ray.z;float shade=.75;if(mode==1||mode==2){t=1e8;if(ray.y<0)t=-1/ray.y;if(mode==1&&-5/ray.z<t){t=-5/ray.z;shade=.65;}if(mode==2){float3 lo=float3(-.55,-.85,-3.1),hi=float3(.55,.15,-2.1);float3 a=lo/ray,b=hi/ray;float3 mn=min(a,b),mx=max(a,b);float near=max(mn.x,max(mn.y,mn.z)),far=min(mx.x,min(mx.y,mx.z));if(near>0&&near<far&&near<t){t=near;shade=.6;}}}if(mode==3){t=frag.x<f.screen.x*.5?-2/ray.z:-8/ray.z;}if(mode==5)t=ray.y<0?-1/ray.y:1e8;if(mode==6)t=3.5/dot(ray,normalize(float3(.3,.4,-1)));if(mode==7)t=200/(-ray.z);float z=t<1e7?.05/(-ray.z*t):0;return {float4(shade,shade,shade,.4),z};}\n";
 NSError *issue=nil;MTLCompileOptions *opt=[MTLCompileOptions new];opt.languageVersion=MTLLanguageVersion3_0;
 id<MTLLibrary> lib=[ctx->device newLibraryWithSource:[src stringByAppendingString:fixture] options:opt error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLPixelFormat formats[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatBGRA8Unorm,MTLPixelFormatRGBA8Unorm_sRGB,MTLPixelFormatBGRA8Unorm_sRGB,MTLPixelFormatRGBA16Float};id<MTLRenderPipelineState> pipelines[5];
 for(int i=0;i<5;i++){MTLRenderPipelineDescriptor *d=[MTLRenderPipelineDescriptor new];d.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];d.fragmentFunction=[lib newFunctionWithName:@"ssao_input_fs"];d.colorAttachments[0].pixelFormat=formats[i];d.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;pipelines[i]=[ctx->device newRenderPipelineStateWithDescriptor:d error:&issue];require(pipelines[i]!=nil,issue.localizedDescription);[d.vertexFunction release];[d.fragmentFunction release];[d release];}
 MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionAlways;id<MTLDepthStencilState> ds=[ctx->device newDepthStencilStateWithDescriptor:dd];
 for(int test=0;test<20;test++){
  int w=test==12?257:384,h=test==12?193:256,fi=test>=5&&test<10?test-5:0;BOOL fallback=test==10;
  uint32_t mode=test<5?test:test==11?3:test==13?0:test==17?5:test==18?6:test==19?7:2;
  id<MTLTexture> color=texture(ctx,w,h,formats[fi],YES),depth=texture(ctx,w,h,MTLPixelFormatDepth32Float,!fallback);Ssao f=frame(w,h);
  if(test==14)f.controls.x=.3;if(test==15)f.controls.z=0;if(test==16)f.controls.z=2;
  input(enc,color,depth,pipelines[fi],ds,f,mode);retire(enc);float *before=pixels(color);
  input(enc,color,depth,pipelines[fi],ds,f,mode);int result=mco_ssao_post(own,enc,color,depth,&f,sizeof f,err,sizeof err);require(result==1,@(err));retire(enc);float *after=pixels(color);
  float biggest=0;int changed=0;for(int i=0;i<w*h;i++){require(fabsf(before[4*i+3]-after[4*i+3])<.002,@"SSAO changed alpha");float diff=before[4*i]-after[4*i];require(diff>=-.004,@"SSAO brightened a pixel");biggest=fmaxf(biggest,diff);if(diff>.008)changed++;}
  if(mode==0||mode==3||mode==4||mode>=5)require(biggest<.008,[NSString stringWithFormat:@"Plane/sky/silhouette self-darkening test %d: %.4f",test,biggest]);
  else if(test==14)require(biggest>.003&&biggest<.05,@"Small radius did not reduce contact reach");
  else require(biggest>.015&&changed>30,[NSString stringWithFormat:@"Missing contact occlusion test %d: %.4f/%d",test,biggest,changed]);
  uint64_t stats[4];mco_ssao_debug_stats(own,stats);require(stats[2]==(uint64_t)((w+1)/2)*((h+1)/2)*8,@"SSAO is not half resolution");require(stats[3]==fallback,@"Wrong depth fallback state");
  printf("case %d format=%lu pattern=%u quality=%.0f max-darkening=%.4f changed=%d\n",test,(unsigned long)formats[fi],mode,f.controls.z,biggest,changed);
  if(test==1||test==2){uint8_t *p=malloc(w*h*4);for(int i=0;i<w*h*4;i++)p[i]=(uint8_t)lrintf(after[i]*255);png([NSString stringWithFormat:@"%s/ssao-%d.png",argv[4],test],p,w,h);for(int i=0;i<w*h*4;i++)p[i]=(uint8_t)lrintf(before[i]*255);png([NSString stringWithFormat:@"%s/before-%d.png",argv[4],test],p,w,h);free(p);}
  // Same frame produces exactly the same result without history or a random pattern.
  input(enc,color,depth,pipelines[fi],ds,f,mode);require(mco_ssao_post(own,enc,color,depth,&f,sizeof f,err,sizeof err)==1,@(err));retire(enc);float *again=pixels(color);for(int i=0;i<w*h*4;i++)require(again[i]==after[i],@"Static SSAO frame flickered");
  free(before);free(after);free(again);[color release];[depth release];
 }
 // Off frees state and encodes no work, including when starting with no state.
 Ssao off=frame(16,16);off.controls.y=0;for(int i=0;i<2;i++){require(mco_ssao_post(own,enc,nil,nil,&off,sizeof off,err,sizeof err)==0,@"Disabled SSAO ran");uint64_t stats[4];mco_ssao_debug_stats(own,stats);require(stats[0]+stats[1]+stats[2]+stats[3]==0,@"Disabled SSAO retained images");}
 // Queue viewport/quality changes without retirement; compare after submission with isolated output.
 id<MTLTexture> colors[6],depths[6];id<MTLCommandBuffer> commands[6];float *expected[6];
 for(int i=0;i<6;i++){int w=255+i*2,h=191+i;Ssao f=frame(w,h);f.controls.z=i%3;colors[i]=texture(ctx,w,h,formats[i%5],YES);depths[i]=texture(ctx,w,h,MTLPixelFormatDepth32Float,YES);input(enc,colors[i],depths[i],pipelines[i%5],ds,f,2);require(mco_ssao_post(own,enc,colors[i],depths[i],&f,sizeof f,err,sizeof err)==1,@(err));retire(enc);expected[i]=pixels(colors[i]);}
 for(int i=0;i<6;i++){Ssao f=frame(colors[i].width,colors[i].height);f.controls.z=i%3;input(enc,colors[i],depths[i],pipelines[i%5],ds,f,2);require(mco_ssao_post(own,enc,colors[i],depths[i],&f,sizeof f,err,sizeof err)==1,@(err));commands[i]=[mc_enc_commit(enc) retain];}
 for(int i=0;i<6;i++){[commands[i] waitUntilCompleted];require(commands[i].status==MTLCommandBufferStatusCompleted,commands[i].error.localizedDescription);float *actual=pixels(colors[i]);for(int p=0;p<colors[i].width*colors[i].height*4;p++)require(actual[p]==expected[i][p],@"Queued SSAO viewport/quality changed output");free(actual);free(expected[i]);[commands[i] release];[colors[i] release];[depths[i] release];}
 printf("SSAO checks passed: 20 fixtures, deterministic output, queued resize/quality, disabled allocation\n");
 if(getenv("SILICONSHADERS_SSAO_BENCH"))for(int size=0;size<2;size++)for(int quality=0;quality<3;quality++){
  int w=size?2560:1920,h=size?1440:1080;Ssao f=frame(w,h);f.controls.z=quality;id<MTLTexture> color=texture(ctx,w,h,formats[0],YES),depth=texture(ctx,w,h,MTLPixelFormatDepth32Float,YES);
  input(enc,color,depth,pipelines[0],ds,f,2);require(mco_ssao_post(own,enc,color,depth,&f,sizeof f,err,sizeof err)==1,@(err));retire(enc);
  double times[24],stage[24],maskTimes[24],applyTimes[24];for(int k=0;k<24;k++){id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,nil,0,0,w,h);require(mc_profile_begin(enc,64),@"No GPU counters");require(mco_ssao_post(own,enc,color,depth,&f,sizeof f,err,sizeof err)==1,@(err));int count=0;id<MTLCounterSampleBuffer> samples=mc_profile_end(enc,&count);id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);double ts[256];mc_profile_read(ctx,samples,count,ts);maskTimes[k]=applyTimes[k]=stage[k]=0;for(int i=0;i<count/4;i++){double duration=0;for(int j=0;j<4;j+=2)if(ts[i*4+j]>=0&&ts[i*4+j+1]>=0)duration+=ts[i*4+j+1]-ts[i*4+j];if(i==0)maskTimes[k]=duration;else if(i==1)applyTimes[k]=duration;stage[k]+=duration;}times[k]=(command.GPUEndTime-command.GPUStartTime)*1000;}
  for(int a=0;a<24;a++)for(int b=a+1;b<24;b++){if(times[b]<times[a]){double v=times[a];times[a]=times[b];times[b]=v;}if(stage[b]<stage[a]){double v=stage[a];stage[a]=stage[b];stage[b]=v;}}printf("SSAO GPU %s %dx%d quality=%d median=%.3f ms p95=%.3f ms (two passes + encoder load)\n",name,w,h,quality,times[12],times[22]);printf("stage median=%.3f ms mask(last)=%.3f apply(last)=%.3f\n",stage[12]/1000,maskTimes[23]/1000,applyTimes[23]/1000);[color release];[depth release];
 }
 mco_ssao_reset(own);return 0;
}}
