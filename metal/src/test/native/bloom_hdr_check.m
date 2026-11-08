// Actual opaque lighting resolve -> original or scene-HDR bloom -> final display before HUD.
#include "sun_test_common.h"
#include "mcsun_surface.h"
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
extern void mco_sun_look(void *,float,float,float);
extern void mco_sun_hdr(void *,int);
extern void mco_sun_materials(void *,const float *,int);
extern void mco_sun_gbuffer(void *,int,int);
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern int mco_camera_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern int mco_camera_hdr_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern void mco_camera_debug_stats(void *,uint64_t *);
extern int mco_upscale(void *,Enc *,id<MTLTexture>,id<MTLTexture>,char *,int);
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor,p[16];} Camera;
static void set(Camera *f,int i,float v){f->p[i/4][i%4]=v;}
static id<MTLTexture> texture(Ctx *ctx,int w,int h,MTLPixelFormat format,BOOL read){MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];d.storageMode=format==MTLPixelFormatDepth32Float?MTLStorageModePrivate:MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget|(read?MTLTextureUsageShaderRead:0);return [ctx->device newTextureWithDescriptor:d];}
static float *readImage(Ctx *ctx,id<MTLTexture> source,id<MTLComputePipelineState> pipe){int count=source.width*source.height;id<MTLBuffer> output=[ctx->device newBufferWithLength:count*16 options:MTLResourceStorageModeShared];id<MTLCommandBuffer> command=[ctx->queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];[c setComputePipelineState:pipe];[c setTexture:source atIndex:0];[c setBuffer:output offset:0 atIndex:0];[c dispatchThreads:MTLSizeMake(source.width,source.height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[c endEncoding];[command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);float *p=malloc(count*16);memcpy(p,output.contents,count*16);[output release];for(int i=0;i<count*4;i++)require(isfinite(p[i])&&p[i]>=0,@"HDR produced negative or nonfinite pixels");return p;}
static float peak(float *p,int count){float top=0;for(int i=0;i<count;i++)for(int c=0;c<3;c++)top=fmaxf(top,p[i*4+c]);return top;}
static double halo(float *p,int w,int h){double value=0;int count=0;for(int y=h/2-h/5;y<h/2+h/5;y++)for(int x=w/2-w/5;x<w/2+w/5;x++){if(abs(x-w/2)<w/15&&abs(y-h/2)<h/10)continue;value+=p[(y*w+x)*4]+p[(y*w+x)*4+1]+p[(y*w+x)*4+2];count++;}return value/(count*3);}
static void save(float *p,int w,int h,NSString *path){uint8_t *rgba=malloc(w*h*4);for(int i=0;i<w*h;i++){for(int c=0;c<3;c++)rgba[i*4+c]=lroundf(fminf(p[i*4+c],1)*255);rgba[i*4+3]=255;}CGColorSpaceRef space=CGColorSpaceCreateDeviceRGB();CGContextRef c=CGBitmapContextCreate(rgba,w,h,8,w*4,space,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);CGImageRef image=CGBitmapContextCreateImage(c);CGImageDestinationRef out=CGImageDestinationCreateWithURL((CFURLRef)[NSURL fileURLWithPath:path],CFSTR("public.png"),1,NULL);CGImageDestinationAddImage(out,image,NULL);require(CGImageDestinationFinalize(out),@"PNG failed");CFRelease(out);CGImageRelease(image);CGContextRelease(c);CGColorSpaceRelease(space);free(rgba);}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==6,@"Usage: bloom_hdr_check TERRAIN SUN CAMERA ORIGINAL_DIR OUTPUT_DIR");NSString *source=@"",*old=@"";for(int i=1;i<=3;i++){source=[source stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:nil]];NSString *name=[@"original-" stringByAppendingString:[@(argv[i]) lastPathComponent]];old=[old stringByAppendingString:[NSString stringWithContentsOfFile:[@(argv[4]) stringByAppendingPathComponent:name] encoding:NSUTF8StringEncoding error:nil]];}
 char error[16384]={0},name[256];Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));Enc *enc=mc_enc_new(ctx);void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error),*original=mco_new(ctx,old.UTF8String,0,0,0,error,sizeof error);require(own&&original,@(error));
 NSError *issue=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:@"#include <metal_stdlib>\nusing namespace metal;kernel void read_image(texture2d<float,access::read> image[[texture(0)]],device float4 *out[[buffer(0)]],uint2 p[[thread_position_in_grid]]){out[p.y*image.get_width()+p.x]=image.read(p);}" options:options error:&issue];id<MTLComputePipelineState> reader=[ctx->device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"read_image"] error:&issue];require(reader!=nil,issue.localizedDescription);
 uint8_t bytes[320]={0};Vertex *v=(Vertex *)bytes;for(int q=0;q<2;q++)for(int c=0;c<4;c++){Vertex *p=v+q*4+c;float size=q?.45:5;p->p[0]=(c==1||c==2)?size:-size;p->p[1]=q?0:-.02;p->p[2]=c>=2?size:-size;memset(p->color,q?255:64,4);p->color[3]=255;p->uv[0]=p->uv[1]=.5;}
 bytes[256]=0;bytes[257]=39;id<MTLBuffer> arena=buffer(ctx,bytes,sizeof bytes);Section section={.count=2};Record records[2]={{.meta=(6u<<6)|(1u<<19),.p0=256},{.start=1,.meta=(6u<<6)|(1u<<19),.p0=257}};uint32_t ids[]={0,1},args[]={384,2,0,0,0};id<MTLBuffer> sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records),list=buffer(ctx,ids,sizeof ids),draw=buffer(ctx,args,sizeof args);
 simd_float4x4 view={.columns={{.2,0,0,0},{0,0,.1,0},{0,.35,0,0},{0,0,1.2,1}}},identity=matrix_identity_float4x4;uint8_t globals[48]={0},terrain[80]={0};((int *)globals)[1]=8;memcpy(terrain,&identity,64);((int *)(terrain+64))[0]=((int *)(terrain+64))[1]=4;float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};id<MTLBuffer> projection=buffer(ctx,&view,64),g=buffer(ctx,globals,48),t=buffer(ctx,terrain,80),f=buffer(ctx,fog,sizeof fog);int64_t uniforms[]={(int64_t)projection,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
 Sun sun={.clip=identity,.cam={0,8,0,0},.sun={.6,.8,0,1},.config={8,1.f/512,.00015,1},.sections=1,.capacity=2,.resolution=512,.on=1};uint8_t packet[400]={0};memcpy(packet,&sun,144);memcpy(packet+144,&view,64);((double *)(packet+208))[1]=8;*(float *)(packet+240)=1.f/60;*(uint32_t *)(packet+248)=1;Sun far=sun;far.config.x=16;memcpy(packet+256,&far,144);OwnSunSurfaceView surface={.clip=view,.camera={0,8,0},.enabled=1,.flags=OWN_SUN_NO_RECEIVER_GRID|OWN_SUN_DEFERRED_LIGHTING};
 MTLPixelFormat outputs[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatBGRA8Unorm,MTLPixelFormatRGBA8Unorm_sRGB,MTLPixelFormatBGRA8Unorm_sRGB,MTLPixelFormatRGBA16Float};int tested=0;
 for(int format=0;format<5;format++)for(int resize=0;resize<2;resize++){
  int width=resize?129:256,height=resize?95:192;float *legacy=NULL;double normalHalo=0,hdrHalo=0;float scenePeak=0;
  for(int mode=0;mode<16;mode++){
   BOOL hdr=mode>=2,readable=mode!=4;void *renderer=mode==0?original:own;
   MTLPixelFormat worldFormat=hdr?(mode==3?MTLPixelFormatRGBA16Float:MTLPixelFormatRG11B10Float):outputs[format];id<MTLTexture> color=texture(ctx,width,height,worldFormat,readable),depth=texture(ctx,width,height,MTLPixelFormatDepth32Float,NO),output=hdr?texture(ctx,width,height,outputs[format],YES):color;
   mco_sun_hdr(renderer,hdr);mco_sun_look(renderer,mode==14?.5:mode==15?2:1,1,1);float settings[15]={1,1,1,3,1,1,1,1,1,1,1,0,1,1,1};mco_sun_materials(renderer,settings,15);mco_sun_gbuffer(renderer,1,0);require(mco_sun_surface(renderer,&surface)==0,@"Surface packet");id<MTLTexture> targets[]={color};float clear[]={1,0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,width,height);
   require(mco_sun_frame(renderer,enc,packet,400,arena,sections,recs,atlas,sampler,error,sizeof error)>0,@(error));uint8_t df[80]={0};*(float *)(df+4)=1;require(mco_draw(renderer,enc,0,df,80,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,@(error));require(mco_sun_finish(renderer,enc,error,sizeof error)==1,@(error));id<MTLCommandBuffer> world=mc_enc_commit(enc);[world waitUntilCompleted];require(world.status==MTLCommandBufferStatusCompleted,world.error.localizedDescription);
   if(readable){float *radiance=readImage(ctx,color,reader);require(hdr?peak(radiance,width*height)>1.2:peak(radiance,width*height)<=1.001,@"World HDR radiance was clipped or Original was changed");if(mode==2)scenePeak=peak(radiance,width*height);if(mode>=14)require(fabs(peak(radiance,width*height)-scenePeak)<.001,@"Exposure was applied before HDR bloom");free(radiance);}
   Camera camera={.inverse=simd_inverse(view),.previous=view,.screen={width,height,1.f/60,0},.light={.8,.2,1,0},.lightColor={1,.9,.75,0}};float values[]={1,1,.3,1.05,.05,1,4,.5,0,1,8,4,8,1,1,12,6,0,1,8,32,12,0,.3,5,.4,.08,.2,.2,8,1,0,0,1,.2,2,0,.3,.75,.4,1,0,0,0,0,.3,1,.1,0,.35,.015,32,16,.25,.65,.3,1,0,hdr};for(int i=0;i<59;i++)set(&camera,i,values[i]);camera.p[15]=(simd_float4){0,-1,0,0};if(mode==5)set(&camera,1,0);if(mode==6){set(&camera,22,1);set(&camera,32,1);set(&camera,36,1);set(&camera,44,1);}
   if(mode==7||mode==8){set(&camera,48,1);set(&camera,57,mode==8);}
   if(mode==9){set(&camera,8,1);set(&camera,9,0);}
   if(mode==10){set(&camera,17,1);camera.light.w=1;camera.p[15].w=1;camera.previous.columns[3].x+=.05;}
   if(mode==11)set(&camera,0,0);if(mode==12)set(&camera,2,0);
   if(mode==13){set(&camera,6,1);set(&camera,7,.25);}

   id<MTLTexture> resumedTargets[]={color};float resumedLoad[5]={0};mc_render_begin(enc,1,resumedTargets,resumedLoad,depth,0,0,width,height);
   uint64_t before[6];mco_camera_debug_stats(renderer,before);
   require((hdr?mco_camera_hdr_post(renderer,enc,color,depth,output,&camera,sizeof camera,error,sizeof error):mco_camera_post(renderer,enc,color,depth,&camera,sizeof camera,error,sizeof error))==1,@(error));require(enc->render&&enc->colors[0]==color&&enc->depth==depth,@"Camera resolve did not restore original world attachments");
   if(mode==13){id<MTLTexture> enlarged=texture(ctx,width*2,height*2,outputs[format],YES);require(mco_upscale(renderer,enc,output,enlarged,error,sizeof error)==1,@(error));require(enc->render&&enc->colors[0]==color&&enc->depth==depth,@"HDR MetalFX changed resumed attachments");id<MTLCommandBuffer> scaled=mc_enc_commit(enc);[scaled waitUntilCompleted];require(scaled.status==MTLCommandBufferStatusCompleted,scaled.error.localizedDescription);float *scaledImage=readImage(ctx,enlarged,reader);require(peak(scaledImage,width*height*4)>.01&&peak(scaledImage,width*height*4)<=1.001,@"HDR MetalFX image invalid");free(scaledImage);[enlarged release];}
   id<MTLCommandBuffer> post=mc_enc_commit(enc);[post waitUntilCompleted];require(post.status==MTLCommandBufferStatusCompleted,post.error.localizedDescription);float *p=readImage(ctx,output,reader);require(peak(p,width*height)<=1.001,@"Final display not tone mapped");for(int i=0;i<width*height;i++)require(fabs(p[i*4+3]-1)<.005,@"Opaque frame alpha changed");
   if(mode==0){legacy=p;normalHalo=halo(p,width,height);}else{
    if(mode==1){for(int i=0;i<width*height*4;i++)require(p[i]==legacy[i],@"Original bloom does not match frozen source");}
    if(mode==14)require(halo(p,width,height)<hdrHalo*.9,[NSString stringWithFormat:@"Final HDR exposure control ineffective: %g vs %g",halo(p,width,height),hdrHalo]);
    if(mode==2){hdrHalo=halo(p,width,height);require(halo(p,width,height)>normalHalo+.001,@"HDR bloom did not retain extra emitter energy");uint64_t stats[6];mco_camera_debug_stats(renderer,stats);require(stats[1]==before[1],@"Readable HDR source required a scene copy");}
    if(format==0&&resize==0&&(mode==1||mode==2))save(p,width,height,[@(argv[5]) stringByAppendingPathComponent:mode==1?@"bloom-original.png":@"bloom-hdr.png"]);free(p);
   }
   [color release];[depth release];if(hdr)[output release];tested++;
  }
  free(legacy);printf("PASS bloom HDR format=%lu size=%dx%d: frozen Original parity, >1 scene values, HDR halo, tone-only/master-off resolve, controls and fallback copy\n",(unsigned long)outputs[format],width,height);fflush(stdout);
 }
 printf("PASS: %d production HDR/original bloom cases\n",tested);return 0;
}}
