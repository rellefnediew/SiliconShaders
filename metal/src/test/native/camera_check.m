// Real optional camera passes on an offscreen target. No Minecraft window.
#include "sun_test_common.h"
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
extern int mco_camera_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern void mco_camera_reset(void *);
extern void mco_camera_debug_stats(void *,uint64_t *);
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor,p[16];} Camera;
static void set(Camera *f,int i,float v){f->p[i/4][i%4]=v;}
static Camera defaults(int width,int height){
 Camera f={.inverse=matrix_identity_float4x4,.previous=matrix_identity_float4x4,.screen={width,height,1.f/60,0},.light={.8,.2,1,0},.lightColor={1,.9,.75,0}};
 float v[]={1,0,.2,.7,.2,1,3,.5,0,1,8,4,8,1,1,12,6,0,1,8,32,12,0,.3,5,.4,.08,.2,.2,8,1,0,0,1,.2,2,0,.3,.75,.4,1,0,0,0,0,.3,1,.1};
 for(int i=0;i<48;i++)set(&f,i,v[i]);
 float focal=1.3f,near=.05f;simd_float4x4 projection={.columns={{focal/((float)width/height),0,0,0},{0,focal,0,0},{0,0,0,-1},{0,0,near,0}}};f.inverse=simd_inverse(projection);f.previous=projection;f.p[15]=(simd_float4){0,0,-1,0};return f;
}
static void png(const char *path,uint8_t *pixels,int width,int height){
 uint8_t *display=malloc(width*height*4);memcpy(display,pixels,width*height*4);for(int at=0;at<width*height;at++)display[at*4+3]=255;
 CGColorSpaceRef space=CGColorSpaceCreateDeviceRGB();CGContextRef c=CGBitmapContextCreate(display,width,height,8,width*4,space,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);CGImageRef i=CGBitmapContextCreateImage(c);CGImageDestinationRef d=CGImageDestinationCreateWithURL((CFURLRef)[NSURL fileURLWithPath:@(path)],CFSTR("public.png"),1,NULL);CGImageDestinationAddImage(d,i,NULL);require(CGImageDestinationFinalize(d),@"PNG write failed");CFRelease(d);CGImageRelease(i);CGContextRelease(c);CGColorSpaceRelease(space);free(display);
}
static double contrast(uint8_t *p,int width,int height,int x0,int x1){
 double sum=0,sq=0,n=0;for(int y=height/4;y<height*3/4;y++)for(int x=x0;x<x1;x++){double v=p[(y*width+x)*4];sum+=v;sq+=v*v;n++;}return sq/n-pow(sum/n,2);
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==5,@"Usage: camera_check TERRAIN SUN CAMERA OUTPUT_DIR");
 NSString *source=@"";for(int i=1;i<=3;i++)source=[source stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:nil]];
 NSString *test=@"\nstruct CamTestOut{float4 color[[color(0)]];float depth[[depth(any)]];};fragment CamTestOut camera_input_fs(float4 p[[position]],constant uint &mode[[buffer(0)]],constant float2 &size[[buffer(1)]]){float2 uv=p.xy/size;float c=((uint(p.x)/2+uint(p.y)/2)&1)?0.9:0.1;float4 color=float4(c,c,c,0.4);float distance=uv.x<.5?8:24;float z=.05/distance;if(mode==0){color=float4(.5,.5,.5,.4);z=0;}if(mode==1){color=float4(0,0,0,.4);if(abs(uv.x-.25)<.03&&abs(uv.y-.5)<.03)color.rgb=1;z=0;}if(mode==4){color=float4(.5,.5,.5,.4);z=.05/8;}return {color,z};}\n";
 char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));Enc *enc=mc_enc_new(ctx);
 NSError *issue=nil;MTLCompileOptions *opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_0;opts.mathMode=MTLMathModeFast;
 id<MTLLibrary> lib=[ctx->device newLibraryWithSource:[source stringByAppendingString:test] options:opts error:&issue];require(lib!=nil,issue.localizedDescription);
 id<MTLRenderPipelineState> input[4];MTLPixelFormat formats[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatRGBA8Unorm_sRGB,MTLPixelFormatBGRA8Unorm,MTLPixelFormatRGBA16Float};
 for(int i=0;i<4;i++){MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"camera_input_fs"];pd.colorAttachments[0].pixelFormat=formats[i];pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;input[i]=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(input[i]!=nil,issue.localizedDescription);}
 MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionAlways;id<MTLDepthStencilState> ds=[ctx->device newDepthStencilStateWithDescriptor:dd];
 int toggles[]={1,8,17,22,32,36,44};uint8_t baseline[256*192*4]={0};uint64_t stats[6]={0};int checked=0;
 for(int mode=0;mode<18;mode++){
  int width=mode==13?129:256,height=mode==13?95:192,format=mode>=10&&mode<=12?mode-9:0;
  BOOL fallback=mode==14;MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:formats[format] width:width height:height mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget|(fallback?0:MTLTextureUsageShaderRead);id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
  td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
  id<MTLTexture> targets[]={color};float clear[]={1,0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,width,height);
  uint pattern=mode==1||mode==5||mode==15||mode==17?0:mode==2?1:mode==16?4:2; // 0 constant sky; 1 point light; 2 depth/checker
  id<MTLCommandBuffer> command=mc_frame_cmd(enc);MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=color;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;rp.colorAttachments[0].storeAction=MTLStoreActionStore;rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionDontCare;rp.depthAttachment.storeAction=MTLStoreActionStore;
  id<MTLRenderCommandEncoder> r=[command renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:input[format]];[r setDepthStencilState:ds];[r setFragmentBytes:&pattern length:4 atIndex:0];simd_float2 size={width,height};[r setFragmentBytes:&size length:8 atIndex:1];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];
  Camera f=defaults(width,height);if(mode>=1&&mode<=7)set(&f,toggles[mode==1?5:mode==2?0:mode==3?1:mode==4?2:mode==5?3:mode==6?4:6],1);
  if(mode==1)set(&f,37,.8);
  if(mode==3){set(&f,9,0);set(&f,11,1);set(&f,12,32);} // pixels at 1080 lines
  if(mode==4){f.light.w=1;f.p[15].w=1;f.previous=simd_mul(f.previous,(simd_float4x4){.columns={{1,0,0,0},{0,1,0,0},{0,0,1,0},{.5,0,0,1}}});}
  if(mode>=8&&mode<=14){for(int i=0;i<7;i++)set(&f,toggles[i],1);f.light.w=0;set(&f,9,1);}
  if(mode==9){f.light.w=1;f.p[15].w=1;f.previous.columns[3].x+=.02;}
  if(mode==15||mode==16)set(&f,22,1);
  if(mode==17){set(&f,17,1);f.light.w=1;f.p[15].w=0;}
  BOOL active=mode!=0&&mode!=17;int result=mco_camera_post(own,enc,color,depth,&f,sizeof f,error,sizeof error);require(result==(active?1:0),[NSString stringWithFormat:@"mode %d result %d: %s",mode,result,error]);
  command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
  mco_camera_debug_stats(own,stats);int bytes=format==3?8:4;uint8_t *raw=calloc(width*height,bytes),*pixels=calloc(width*height,4);[color getBytes:raw bytesPerRow:width*bytes fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
  if(format==3){for(int i=0;i<width*height*4;i++){_Float16 h;memcpy(&h,raw+i*2,2);require(isfinite((float)h),@"Half-float output contains NaN");pixels[i]=(uint8_t)lrintf(fminf(1,fmaxf(0,(float)h))*255);}}else memcpy(pixels,raw,width*height*4);
  if(mode==0){require(stats[0]==0&&stats[5]==0,@"Disabled camera allocated GPU resources");memcpy(baseline,pixels,width*height*4);}
  if(mode==1){int center=((height/2)*width+width/2)*4,corner=(2*width+2)*4;require(pixels[center]>120&&pixels[corner]<pixels[center]-20,@"Vignette did not darken only edges");require(stats[0]==1&&stats[1]==0&&stats[2]==0&&stats[4]==1,@"Apple framebuffer fetch unexpectedly copied the full image");}
  if(mode==2){int x=width/4+12,y=height/2;require(pixels[(y*width+x)*4]>1,@"Bloom did not spread beyond the bright source");require(stats[1]==0,@"Readable bloom input was copied unnecessarily");}
  if(mode==3){double near=contrast(pixels,width,height,width/8,width*3/8),far=contrast(pixels,width,height,width*5/8,width*7/8);printf("DOF contrast near %.1f far %.1f\n",near,far);require(near>5000&&far<near*.6,@"Focus plane was blurred or distant plane stayed sharp");}
  if(mode==4){require(contrast(pixels,width,height,width/8,width*3/8)<contrast(baseline,width,height,width/8,width*3/8)*.8,@"Motion did not blur the checker pattern");}
  if(mode==14)require(stats[1]>0&&stats[2]>0,@"Non-readable target did not use safe input copies");
  if(mode==15){int changed=0;for(int i=0;i<width*height;i++)changed+=pixels[i*4]>130;require(changed>100,@"Visible sun produced no flare");}
  if(mode==16){int max=0;for(int i=0;i<width*height;i++)max=MAX(max,pixels[i*4]);require(max<=129,@"Occluded sun leaked lens flares");}
  for(int i=0;i<width*height;i++)require(abs((int)pixels[i*4+3]-102)<=1,@"Camera changed world alpha");
  char file[2048];snprintf(file,sizeof file,"%s/camera-%02d.png",argv[4],mode);png(file,pixels,width,height);
  printf("mode %d: mask %llu passes %llu colorCopies %llu depthCopies %llu fetch %llu bytes %llu\n",mode,stats[3],stats[0],stats[1],stats[2],stats[4],stats[5]);fflush(stdout);
  mco_camera_reset(own);free(raw);free(pixels);[color release];[depth release];checked++;
 }
 // Queue changing masks and odd viewport sizes without waiting between frames.
 // Each output must equal its own independently retired reference.
 id<MTLTexture> queued[6]={nil};id<MTLBuffer> references[6]={nil};id<MTLCommandBuffer> pending[6]={nil};
 void *referenceOwn=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(referenceOwn!=NULL,@(error));
 for(int phase=0;phase<2;phase++)for(int i=0;i<6;i++){
  int width=96+(i%2),height=64+(i%3),n=width*height;uint8_t *initial=malloc(n*4);for(int k=0;k<n;k++){initial[k*4]=initial[k*4+1]=initial[k*4+2]=((k%width)/2+(k/width)/2)&1?210:30;initial[k*4+3]=102;}
  MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];[color replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:initial bytesPerRow:width*4];free(initial);
  td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];id<MTLTexture> targets[]={color};float load[5]={0};mc_render_begin(enc,1,targets,load,depth,1,.00625,width,height);
  Camera f=defaults(width,height);for(int j=0;j<7;j++)set(&f,toggles[j],i==0?0:i==1?(j==0):i==2?(j==1):i==3?(j==4):i==4?(j==3):1);set(&f,9,0);
  void *renderer=phase==0?referenceOwn:own;int result=mco_camera_post(renderer,enc,color,depth,&f,sizeof f,error,sizeof error);require(result==(i==0?0:1),@(error));id<MTLCommandBuffer> command=mc_enc_commit(enc);
  if(phase==0){[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);references[i]=[ctx->device newBufferWithLength:n*4 options:MTLResourceStorageModeShared];[color getBytes:references[i].contents bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];[color release];}
  else {queued[i]=color;pending[i]=[command retain];}
  [depth release];
 }
 [pending[5] waitUntilCompleted];
 for(int i=0;i<6;i++){require(pending[i].status==MTLCommandBufferStatusCompleted,pending[i].error.localizedDescription);NSUInteger width=queued[i].width,height=queued[i].height;uint8_t *pixels=malloc(width*height*4);[queued[i] getBytes:pixels bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];require(!memcmp(pixels,references[i].contents,width*height*4),@"Queued mask/viewport changes used stale or overwritten camera textures");free(pixels);[queued[i] release];[references[i] release];[pending[i] release];}
 mco_camera_reset(own);mco_camera_reset(referenceOwn);puts("PASS: six queued mask/viewport changes match independent references without intermediate waits");
 Camera invalid=defaults(1,1);invalid.p[0].x=NAN;require(mco_camera_post(own,enc,nil,nil,&invalid,sizeof invalid,error,sizeof error)<0,@"Nonfinite packet accepted");mco_camera_debug_stats(own,stats);require(!stats[0]&&!stats[5],@"Reset retained camera allocations");
 printf("PASS: %d actual camera render cases, independent effects, framebuffer fetch/copy paths, sharp focus plane, motion, occluded flares, formats, resize, alpha and allocation-free disable\n",checked);
}return 0;}
