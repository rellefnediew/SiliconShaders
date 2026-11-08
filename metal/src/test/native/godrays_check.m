// Actual shadow-map casters + volumetric integration + depth-aware composition. No game/window.
#include "sun_test_common.h"
#include "godrays_reference.h"
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
extern int mco_camera_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern void mco_camera_reset(void *);
extern int mco_camera_debug_rays_reference(void *,int);
extern void mco_camera_debug_stats(void *,uint64_t *);
extern id<MTLTexture> mco_sun_debug_cascade_map(void *,int);
extern void mco_sun_debug_stats(void *,uint64_t *);
extern void mco_sun_moon_light(void *,int);
extern void mco_sun_look(void *,float,float,float);
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor,p[16];} Camera;
static void set(Camera *f,int i,float v){f->p[i/4][i%4]=v;}
static Camera settings(int w,int h){
 Camera f={.screen={w,h,1.f/60,0},.light={-10,-10,0,0},.lightColor={1,.9,.75,0}};
 float values[]={1,0,.2,.7,.2,1,3,.5,0,1,8,4,8,1,1,12,6,0,1,8,32,12,0,.3,5,.4,.08,.2,.2,8,1,0,0,1,.2,2,0,.3,.75,.4,1,0,0,0,0,.3,1,.1,1,.6,.025,32,32,.25,.65,.3,1};
 for(int i=0;i<57;i++)set(&f,i,values[i]);
 float focal=1/tanf(.4f*.5f);simd_float4x4 projection={.columns={{focal/((float)w/h),0,0,0},{0,focal,0,0},{0,0,0,-1},{0,0,.05,0}}};f.inverse=simd_inverse(projection);f.previous=projection;f.p[15]=(simd_float4){0,0,-1,0};return f;
}
static uint32_t bound(int x,int y,int z){return (uint32_t)(x+16)|((uint32_t)(y+16)<<8)|((uint32_t)(z+16)<<16);}
static void canopy(Vertex *v,int q,float x0,float x1){for(int c=0;c<4;c++){Vertex *a=v+q*4+c;a->p[0]=c==1||c==2?x1:x0;a->p[1]=6;a->p[2]=c>=2?-32:4;memset(a->color,255,4);a->uv[0]=a->uv[1]=.5;a->light[1]=240;}}
static void png(NSString *path,uint8_t *pixels,int w,int h){
 uint8_t *display=malloc(w*h*4);memcpy(display,pixels,w*h*4);for(int i=0;i<w*h;i++)display[i*4+3]=255;
 CGColorSpaceRef space=CGColorSpaceCreateDeviceRGB();CGContextRef c=CGBitmapContextCreate(display,w,h,8,w*4,space,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);CGImageRef image=CGBitmapContextCreateImage(c);CGImageDestinationRef d=CGImageDestinationCreateWithURL((CFURLRef)[NSURL fileURLWithPath:path],CFSTR("public.png"),1,NULL);CGImageDestinationAddImage(d,image,NULL);require(CGImageDestinationFinalize(d),@"PNG failed");CFRelease(d);CGImageRelease(image);CGContextRelease(c);CGColorSpaceRelease(space);free(display);
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==5,@"Usage: godrays_check TERRAIN SUN CAMERA OUTPUT_DIRECTORY");NSString *source=@"";for(int i=1;i<=3;i++)source=[source stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:nil]];
 BOOL paired=getenv("SILICONSHADERS_GODRAYS_REFERENCE")!=NULL;
 if(paired)source=[source stringByAppendingString:godraysReference(@(getenv("SILICONSHADERS_GODRAYS_REFERENCE")))];
 char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));Enc *enc=mc_enc_new(ctx);
 id<MTLTexture> atlas=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
 NSError *issue=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;
 NSString *edgeShader=@"\nstruct RayEdgeOutput{float4 color [[color(0)]];float depth [[depth(any)]];};fragment RayEdgeOutput camera_ray_edge_fs(float4 p [[position]]) {return {float4(.08,.08,.08,.4),p.x<128?.25:.05/24};}\n";
 id<MTLLibrary> edgeLibrary=[ctx->device newLibraryWithSource:[source stringByAppendingString:edgeShader] options:options error:&issue];require(edgeLibrary!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[edgeLibrary newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[edgeLibrary newFunctionWithName:@"camera_ray_edge_fs"];pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
 id<MTLRenderPipelineState> edgePipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(edgePipeline!=nil,issue.localizedDescription);MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new];dd.depthCompareFunction=MTLCompareFunctionAlways;dd.depthWriteEnabled=YES;id<MTLDepthStencilState> edgeDepth=[ctx->device newDepthStencilStateWithDescriptor:dd];
 uint8_t *pairedReferences[30]={0},*references[2][30]={{0}};id<MTLTexture> queued[30]={nil};id<MTLCommandBuffer> pending[30]={nil};
 uint8_t *openPixels=NULL;double dayMean=0;int checked=0;
 int originalPhase=paired?1:0,optimizedPhase=paired?2:1,queuedPhase=paired?3:2;
 for(int phase=0;phase<=queuedPhase;phase++)for(int mode=0;mode<30;mode++){@autoreleasepool{
  BOOL queueCase=mode==3||mode==6||mode==10||mode==11||mode==17||mode==18;if(phase==queuedPhase&&!queueCase)continue;
  if(paired)require(mco_camera_debug_rays_reference(own,phase==0)==0,@"Frozen reference unavailable");
  int w=mode==16?1:mode==17?129:256,h=mode==16?1:mode==17?95:192;
  MTLPixelFormat format=mode==12?MTLPixelFormatRGBA8Unorm_sRGB:mode==13?MTLPixelFormatBGRA8Unorm:mode==14?MTLPixelFormatRGBA16Float:MTLPixelFormatRGBA8Unorm;
  BOOL copied=mode==11;MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;
  id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|(copied?0:MTLTextureUsageShaderRead);id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
  id<MTLTexture> targets[]={color};float clear[]={1,.08,.08,.08,.4};float z=mode==7?.25:mode==25?0:.05/24;mc_render_begin(enc,1,targets,clear,depth,1,z,w,h);
  Vertex vertices[12]={0};int count=mode==2?1:mode==3||mode==28?3:0;
  if(count==1)canopy(vertices,0,-12,12);if(count==3){canopy(vertices,0,-8,-5);canopy(vertices,1,-2,1);canopy(vertices,2,4,7);}
  Section section={.count=count};Record records[3]={0};for(int i=0;i<3;i++){records[i].start=i;records[i].meta=6u<<6;records[i].lo=bound(-12,5,-16);records[i].hi=bound(12,7,0);}
  id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records);
  int levels=mode==18?4:mode==17?3:2;uint8_t packet[688]={0};
  for(int i=0;i<levels;i++){
   float range=i==0?8:mode==25?192:levels==2?32:8+(float)i*24/(levels-1);
   Sun sun={.clip={.columns={{1/range,0,0,0},{0,0,-1.f/16,0},{0,1/range,0,0},{0,0,.5,1}}},.sun={0,1,0,mode==4?0:1},.config={range,1.f/512,.00015,1},.sections=1,.resolution=mode==9?0:512,.on=1};
   if(mode==28){float angle=.37f;simd_float4x4 turn={.columns={{cosf(angle),0,-sinf(angle),0},{0,1,0,0},{sinf(angle),0,cosf(angle),0},{0,0,0,1}}};sun.clip=simd_mul(sun.clip,turn);}
   memcpy(packet+(i?256+(i-1)*144:0),&sun,144);
  }
  *(uint32_t *)(packet+252)=levels;mco_sun_moon_light(own,mode==5||mode==24);mco_sun_look(own,mode==27?4:1,mode==27?2:1,1);
  require(mco_sun_frame(own,enc,packet,sizeof packet,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,@(error));
  if(mode==22){[enc->render setRenderPipelineState:edgePipeline];[enc->render setDepthStencilState:edgeDepth];[enc->render setCullMode:MTLCullModeNone];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];}
  Camera f=settings(w,h);if(mode==0||mode==19)set(&f,48,0);if(mode==8)set(&f,49,0);if(mode==6)set(&f,59,1);if(mode==20)set(&f,59,-1);if(mode==21){set(&f,59,1);set(&f,56,0);}
  if(mode==10){set(&f,53,.5);set(&f,52,64);}if(mode==15){set(&f,1,1);set(&f,36,1);set(&f,44,1);set(&f,32,1);}if(mode==19){set(&f,48,1);simd_float4x4 shift=matrix_identity_float4x4;shift.columns[3].x=100;f.inverse=simd_mul(shift,f.inverse);}
  if(mode==23){set(&f,49,2);set(&f,50,.001);set(&f,52,64);set(&f,54,.85);}
  if(mode==24){set(&f,55,2);set(&f,59,1);}
  if(mode==25){set(&f,51,192);set(&f,52,8);}
  if(mode==26){set(&f,49,.02);set(&f,50,.08);set(&f,52,8);set(&f,54,0);set(&f,56,3);set(&f,59,1);}
  if(mode==28){float angle=.55f;simd_float4x4 turn={.columns={{cosf(angle),0,-sinf(angle),0},{0,1,0,0},{sinf(angle),0,cosf(angle),0},{0,0,0,1}}};f.inverse=simd_mul(turn,f.inverse);}
  if(mode==29){
   const int toggles[]={1,8,17,22,32,36,44};for(int i=0;i<7;i++)set(&f,toggles[i],1);
   f.light=(simd_float4){.6,.4,1,1};f.p[15].w=1;f.previous.columns[3].x+=.05f;
  }
  set(&f,57,phase==optimizedPhase||(phase==queuedPhase&&(mode&1)));
  BOOL active=mode!=0&&mode!=4&&mode!=8&&mode!=9&&mode!=20&&mode!=21;
  uint64_t mapsBefore[2],mapsAfter[2];mco_sun_debug_stats(own,mapsBefore);
  int ran=mco_camera_post(own,enc,color,depth,&f,sizeof f,error,sizeof error);require(ran==(active?1:0),[NSString stringWithFormat:@"mode %d ran %d: %s",mode,ran,error]);
  mco_sun_debug_stats(own,mapsAfter);require(mapsAfter[0]==mapsBefore[0],@"Godrays rendered additional shadow maps");
  id<MTLCommandBuffer> command=mc_enc_commit(enc);
  if(phase==queuedPhase){queued[mode]=[color retain];pending[mode]=[command retain];[color release];[depth release];[arena release];[sections release];[recs release];continue;}
  [command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
  int bytes=format==MTLPixelFormatRGBA16Float?8:4;uint8_t *raw=calloc(w*h,bytes),*pixels=calloc(w*h,4);[color getBytes:raw bytesPerRow:w*bytes fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];
  for(int i=0;i<w*h*4;i++){if(bytes==8){_Float16 value;memcpy(&value,raw+i*2,2);require(isfinite((float)value),@"Nonfinite rays output");pixels[i]=(uint8_t)lrintf(fminf(1,fmaxf(0,(float)value))*255);}else pixels[i]=raw[i];}
  if(format==MTLPixelFormatBGRA8Unorm)for(int i=0;i<w*h;i++){uint8_t r=pixels[i*4];pixels[i*4]=pixels[i*4+2];pixels[i*4+2]=r;}
  double sum=0;int minimum=255,maximum=0,n=0;for(int y=h/4;y<MAX(h/4+1,h*3/4);y++)for(int x=w/4;x<MAX(w/4+1,w*3/4);x++){int i=y*w+x;sum+=pixels[i*4];minimum=MIN(minimum,pixels[i*4]);maximum=MAX(maximum,pixels[i*4]);n++;}double mean=sum/MAX(n,1);
  uint64_t stats[6]={0};mco_camera_debug_stats(own,stats);
  if(!active)require(stats[0]==0&&stats[5]==0,@"Inactive godrays allocated or encoded work");
  if(active&&mode!=15&&mode!=29)require(stats[0]==2&&stats[1]==0&&stats[2]==copied&&stats[4]==1,@"Godrays did not use low-resolution + shared Apple composition");
  if(mode==29)require(stats[3]==255&&stats[0]==14,@"All eight camera effects did not retain their combined passes");
  if(mode==1){dayMean=mean;require(mean>22,@"Open air did not scatter light");openPixels=malloc(w*h*4);memcpy(openPixels,pixels,w*h*4);}
  if(mode==2){
   for(int level=0;level<2;level++){id<MTLTexture> map=mco_sun_debug_cascade_map(own,level);id<MTLBuffer> read=[ctx->device newBufferWithLength:512*512*4 options:MTLResourceStorageModeShared];id<MTLCommandBuffer> cb=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];[blit copyFromTexture:map sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(512,512,1) toBuffer:read destinationOffset:0 destinationBytesPerRow:2048 destinationBytesPerImage:512*512*4];[blit endEncoding];[cb commit];[cb waitUntilCompleted];float *depths=read.contents;printf("roof map %d: center %f sample %f\n",level,depths[256*512+256],depths[192*512+256]);[read release];}
   printf("roof mean %.4f max %d\n",mean,maximum);fflush(stdout);
   png([@(argv[4]) stringByAppendingPathComponent:@"roof-debug.png"],pixels,w,h);
  }
  if(mode==2)require(maximum<=21,@"Closed roof leaked light shafts");
  if(mode==3)require(maximum-minimum>8&&mean<dayMean-1,@"Actual canopy gaps did not modulate shafts");
  if(mode==5)require(mean>20&&mean<dayMean-2,@"Moon rays not dimmer than sunlight");
  if(mode==6){int i=((h/2)*w+w/2)*4;require(pixels[i+1]>=pixels[i]&&pixels[i+2]>=pixels[i],@"Underwater light not tinted");}
  if(mode==7)require(mean<dayMean-2,@"Shallow opaque depth did not stop integration");
  if(mode==22){for(int y=20;y<h-20;y++){require(pixels[(y*w+127)*4]<=22,@"Low-resolution rays leaked onto foreground depth edge");require(pixels[(y*w+128)*4]>40,@"Depth-aware upsampling erased background beams");}}
  if(mode==19)require(maximum<=21,@"Missing shadow coverage created false beams");
  for(int i=0;i<w*h;i++)require(abs((int)pixels[i*4+3]-102)<=1,@"Godrays changed alpha");
  png([@(argv[4]) stringByAppendingPathComponent:[NSString stringWithFormat:@"godrays-%02d.png",mode]],pixels,w,h);
  printf("mode %d: mean %.3f min %d max %d passes %llu color copies %llu depth copies %llu bytes %llu\n",mode,mean,minimum,maximum,stats[0],stats[1],stats[2],stats[5]);fflush(stdout);
  if(mode==18){set(&f,48,0);int off=mco_camera_post(own,enc,color,depth,&f,sizeof f,error,sizeof error);require(off==0,@"Disable retained rays pass");mco_camera_debug_stats(own,stats);require(stats[5]==0,@"Disable retained rays textures");}
  if(paired){
   if(phase==0){pairedReferences[mode]=malloc(w*h*4);memcpy(pairedReferences[mode],pixels,w*h*4);}
   else {int largest=0,bad=0;for(int i=0;i<w*h*4;i++){int d=abs((int)pairedReferences[mode][i]-(int)pixels[i]);largest=MAX(largest,d);bad+=d>1;}printf("paired mode %d: max channel difference %d, channels >1: %d\n",mode,largest,bad);require(bad==0,@"Optimized image differs by more than one 8-bit step");if(phase==originalPhase)require(largest==0,@"Selectable Original differs from frozen original implementation");if(phase==optimizedPhase)free(pairedReferences[mode]);}
  }
  if(queueCase&&(phase==originalPhase||phase==optimizedPhase)){int variant=phase==optimizedPhase;references[variant][mode]=malloc(w*h*4);memcpy(references[variant][mode],pixels,w*h*4);}
  mco_camera_reset(own);free(raw);free(pixels);[color release];[depth release];[arena release];[sections release];[recs release];checked++;
 }}
 [pending[18] waitUntilCompleted];int queues=0;
 for(int i=0;i<30;i++)if(queued[i]){
  require(pending[i].status==MTLCommandBufferStatusCompleted,pending[i].error.localizedDescription);NSUInteger width=queued[i].width,height=queued[i].height;uint8_t *pixels=malloc(width*height*4);[queued[i] getBytes:pixels bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];require(!memcmp(pixels,references[i&1][i],width*height*4),@"Queued godray frames differ from retired references");free(pixels);free(references[0][i]);free(references[1][i]);[queued[i] release];[pending[i] release];queues++;
 }mco_camera_reset(own);printf("PASS: %d queued godray frames switching Original/Optimized without intermediate waits match independent references\n",queues);
 free(openPixels);printf("PASS: %d real godray cases, shadowed roofs/canopy gaps, offscreen source, depth stops, moon/water, four formats, 2/3/4 cascades, quality, resize, combined effects, zero-work disable and alpha\n",checked/(paired?3:2));
}return 0;}
