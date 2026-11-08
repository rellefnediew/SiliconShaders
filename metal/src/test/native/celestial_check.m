// The production celestial shader AND its sunSkyPixel integration, with real GPU pixels and API validation.
#include "sun_test_common.h"
#import <ImageIO/ImageIO.h>
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
typedef struct {simd_float4x4 inverseView,previousView;simd_float4 cameraDelta,config;} Temporal;
typedef struct {float color[4],start,end,renderStart,renderEnd,skyEnd,cloudEnd;} Fog;
_Static_assert(sizeof(Lighting)==864,"Celestial lighting ABI");
static const int W=256,H=256;
static id<MTLTexture> image(Ctx *ctx,int w,int h,MTLPixelFormat format){
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
 d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;return[ctx->device newTextureWithDescriptor:d];
}
static void save(float *pixels,NSString *path){
 uint8_t *rgba=malloc(W*H*4);for(int i=0;i<W*H;i++){for(int c=0;c<3;c++)rgba[i*4+c]=(uint8_t)lroundf(fminf(1,fmaxf(0,pixels[i*4+c]))*255);rgba[i*4+3]=255;}
 CGColorSpaceRef color=CGColorSpaceCreateDeviceRGB();CGContextRef canvas=CGBitmapContextCreate(rgba,W,H,8,W*4,color,(CGBitmapInfo)kCGImageAlphaPremultipliedLast);
 CGImageRef picture=CGBitmapContextCreateImage(canvas);CGImageDestinationRef destination=CGImageDestinationCreateWithURL((CFURLRef)[NSURL fileURLWithPath:path],CFSTR("public.png"),1,NULL);
 CGImageDestinationAddImage(destination,picture,NULL);require(CGImageDestinationFinalize(destination),@"Cannot write preview");CFRelease(destination);CGImageRelease(picture);CGContextRelease(canvas);CGColorSpaceRelease(color);free(rgba);
}
static Lighting base(void){
 Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,1}};
 l.grade[0]=(simd_float4){1,1,1,1};l.grade[2]=(simd_float4){1,1,1,0};l.grade[3]=(simd_float4){1,1,1,1};
 l.celestial[0]=(simd_float4){4,.8,.2,.0523598776};l.celestial[1]=(simd_float4){.3,.7,2,.0523598776};
 l.celestial[2]=(simd_float4){0,0,-1,0};l.celestial[3]=(simd_float4){0,.2,1,0};l.celestial[4]=(simd_float4){1,0,.25,1};return l;
}
static int cases;
static simd_float4 background;
static float *probe(Ctx *ctx,Enc *enc,id<MTLRenderPipelineState> pipeline,Lighting l,BOOL moon,float depth,BOOL below,BOOL integration){
 simd_float3 center=simd_normalize((simd_float3){-.6,below?-.8:.8,0});
 simd_float3 right=simd_normalize(simd_cross((simd_float3){0,0,1},center)),up=simd_cross(center,right);
 Temporal t={.config={W,H,0,0}};
 t.inverseView.columns[0]=(simd_float4){right.x*.14,right.y*.14,right.z*.14,0};
 t.inverseView.columns[1]=(simd_float4){up.x*.14,up.y*.14,up.z*.14,0};
 t.inverseView.columns[2]=(simd_float4){0,0,0,1};t.inverseView.columns[3]=(simd_float4){center.x,center.y,center.z,0};
 simd_float3 physicalSun=moon?-center:center;Sun sun={.sun={physicalSun.x,physicalSun.y,physicalSun.z,1}};
 if(l.moon>.5)sun.sun*=(simd_float4){-1,-1,-1,1};
 Fog fog={.end=1024,.renderEnd=1024,.skyEnd=1024,.cloudEnd=1024};
 id<MTLTexture> target=image(ctx,W,H,MTLPixelFormatRGBA32Float),noise=white(ctx);
 float clear[5]={1,0,0,0,1},u[2]={depth,integration?1:0};id<MTLTexture> targets[]={target};mc_render_begin(enc,1,targets,clear,nil,0,0,W,H);
 [enc->render setRenderPipelineState:pipeline];[enc->render setCullMode:MTLCullModeNone];
 [enc->render setFragmentBytes:&l length:sizeof l atIndex:0];[enc->render setFragmentBytes:&sun length:sizeof sun atIndex:1];
 [enc->render setFragmentBytes:&t length:sizeof t atIndex:2];[enc->render setFragmentBytes:&fog length:sizeof fog atIndex:3];
 [enc->render setFragmentBytes:u length:sizeof u atIndex:4];[enc->render setFragmentTexture:noise atIndex:0];
 [enc->render setFragmentBytes:&background length:sizeof background atIndex:5];
 [enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];id<MTLCommandBuffer> cmd=mc_enc_commit(enc);[cmd waitUntilCompleted];
 require(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.localizedDescription);[cmd release];
 float *out=malloc(W*H*16);[target getBytes:out bytesPerRow:W*16 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
 for(int i=0;i<W*H*4;i++)require(isfinite(out[i])&&out[i]>=0,@"Nonfinite or negative celestial pixel");
 [target release];[noise release];cases++;return out;
}
static double energy(float *p){double result=0;for(int i=0;i<W*H;i++)result+=p[i*4]+p[i*4+1]+p[i*4+2];return result;}
static int area(float *p){int n=0;for(int i=0;i<W*H;i++)n+=p[i*4]+p[i*4+1]+p[i*4+2]>.1;return n;}
static simd_double2 centroid(float *p){simd_double2 sum={0,0};double total=0;for(int y=0;y<H;y++)for(int x=0;x<W;x++){double e=p[(y*W+x)*4]+p[(y*W+x)*4+1]+p[(y*W+x)*4+2];sum+=(simd_double2){x-W*.5,y-H*.5}*e;total+=e;}return sum/fmax(total,1e-10);}
static int transitionPixels(float *p){int n=0;for(int y=H/2-20;y<H/2+20;y++)for(int x=W/2-20;x<W/2+20;x++){float alpha=p[(y*W+x)*4+2]/2;n+=alpha>.01&&alpha<.99;}return n;}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==4,@"Usage: celestial_check TERRAIN SUN OUTPUT_DIR");
 [[NSFileManager defaultManager] createDirectoryAtPath:@(argv[3]) withIntermediateDirectories:YES attributes:nil error:NULL];
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 celestial_probe_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant SunTemporalFrame &frame[[buffer(2)]],constant Fog &fog[[buffer(3)]],constant float2 &u[[buffer(4)]],constant float4 &background[[buffer(5)]],texture2d<float> noise[[texture(0)]]){float2 uv=p.xy/frame.config.xy;float3 c=u.y>.5?sunSkyPixel(background.rgb,uv,u.x,frame,sun,fog,l,noise,noise):sunCelestialBodies(sunSkyDirection(uv,frame),sunSkyPixelSize(uv,sunSkyDirection(uv,frame),frame),sun,l);return float4(c,1);}\n"];
 char name[256],err[16384];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));Enc *enc=mc_enc_new(ctx);
 NSError *issue=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;
 id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:options error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];
 MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool off=false;[fc setConstantValue:&off type:MTLDataTypeBool atIndex:60];
 pd.fragmentFunction=[lib newFunctionWithName:@"celestial_probe_fs" constantValues:fc error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);
 pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;id<MTLRenderPipelineState> pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 [fc setConstantValue:&off type:MTLDataTypeBool atIndex:64];pd.fragmentFunction=[lib newFunctionWithName:@"celestial_probe_fs" constantValues:fc error:&issue];
 id<MTLRenderPipelineState> disabled=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(disabled!=nil,issue.localizedDescription);
 float *specialized=probe(ctx,enc,disabled,base(),NO,0,NO,YES);require(energy(specialized)==0,@"Off specialization still draws celestial bodies");free(specialized);
 Lighting l=base();l.celestial[3].z=0;float *p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);require(energy(p)==0,@"Vanilla mode draws custom bodies");free(p);
 int areas[3];for(int shape=0;shape<3;shape++){l=base();l.celestial[3].z=shape+1;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);areas[shape]=area(p);require(areas[shape]>100,@"Sun missing");free(p);}
 require(areas[1]>areas[0]&&areas[0]>areas[2],@"Round, square and diamond silhouettes do not differ");
 l=base();p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);double solar=energy(p);int small=area(p);free(p);
 l.celestial[2].x=2;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);require(energy(p)>solar&&area(p)>small,@"Solar halo control ineffective");free(p);
 l=base();l.celestial[0].x=.1;l.celestial[0].y=.2;l.celestial[0].z=4;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);
 int center=(H/2*W+W/2)*4;require(p[center+2]>p[center]*30,@"Custom sun RGB ignored");free(p);
 l=base();
 l.celestial[0].w*=2;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);require(area(p)>small*3,@"Size control ineffective");free(p);
 l=base();l.celestial[0].x=l.celestial[0].y=l.celestial[0].z=0;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);require(energy(p)==0,@"Zero solar brightness still draws");free(p);
 l=base();l.env.y=1;l.celestial[3].y=0;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);require(energy(p)==0,@"Rain visibility zero still draws");free(p);
 l.celestial[3].y=.2;p=probe(ctx,enc,pipeline,l,NO,0,NO,NO);require(fabs(energy(p)/solar-.2)<.001,@"Rain visibility ratio incorrect");free(p);
 l=base();p=probe(ctx,enc,pipeline,l,NO,0,YES,NO);require(energy(p)==0,@"Bodies below the horizon");free(p);
 double phases[8];for(int phase=0;phase<8;phase++){l=base();l.celestial[2].z=phase*M_PI/4;p=probe(ctx,enc,pipeline,l,YES,0,NO,NO);phases[phase]=energy(p);free(p);}
 require(phases[0]>0&&phases[4]==0&&phases[2]>phases[3]&&phases[3]>0,@"Moon phases do not produce full/quarter/crescent/new");
 require(fabs(phases[2]/phases[0]-.5)<.03&&fabs(phases[1]-phases[7])/phases[0]<.01,@"Moon phase symmetry or coverage");
 l=base();p=probe(ctx,enc,pipeline,l,YES,0,NO,NO);double full=energy(p);free(p);l.moon=1;p=probe(ctx,enc,pipeline,l,YES,0,NO,NO);require(fabs(energy(p)-full)<.001,@"Moon-light direction changes celestial orbit");free(p);
 for(int hdr=0;hdr<2;hdr++)for(int srgb=0;srgb<2;srgb++){
  l=base();l.materials[3].w=hdr;l.srgb=srgb;p=probe(ctx,enc,pipeline,l,NO,0,NO,YES);require(energy(p)>0,@"Production sky resolve erased sun at zero sky blend");
  if(!hdr&&!srgb)save(p,[@(argv[3]) stringByAppendingPathComponent:@"sun-round.png"]);free(p);
  p=probe(ctx,enc,pipeline,l,NO,.5,NO,YES);require(energy(p)==0,@"Celestial bodies drawn over terrain");free(p);
  l.env.z=1;p=probe(ctx,enc,pipeline,l,NO,0,NO,YES);require(energy(p)==0,@"Bodies drawn through fluid fallback");free(p);
 }
 for(int shape=0;shape<3;shape++){
  l=base();l.celestial[3].z=shape+1;p=probe(ctx,enc,pipeline,l,NO,0,NO,YES);save(p,[@(argv[3]) stringByAppendingPathComponent:[NSString stringWithFormat:@"sun-%d.png",shape]]);free(p);
  l.celestial[3].w=shape;l.celestial[2].z=M_PI/2;l.celestial[2].w=.6;p=probe(ctx,enc,pipeline,l,YES,0,NO,YES);save(p,[@(argv[3]) stringByAppendingPathComponent:[NSString stringWithFormat:@"moon-%d.png",shape]]);free(p);
 }
 // The phase direction rotates independently of silhouette and surface detail, retaining its total illumination.
 for(int shape=0;shape<3;shape++){
  double referenceEnergy=0;for(int angle=0;angle<360;angle+=45){
   l=base();l.celestial[3].w=shape;l.celestial[2].z=M_PI/2;l.celestial[4].x=cos(angle*M_PI/180);l.celestial[4].y=sin(angle*M_PI/180);
   p=probe(ctx,enc,pipeline,l,YES,0,NO,NO);simd_double2 center=centroid(p);double e=energy(p);if(angle==0)referenceEnergy=e;
   require(fabs(e-referenceEnergy)/referenceEnergy<.01,@"Rotation changed the illuminated fraction");
   require(center.x*cos(angle*M_PI/180)+center.y*sin(angle*M_PI/180)>8,@"Phase rotation ignored or reversed");free(p);
  }
 }
 // The user-controlled penumbra is wider than the mandatory one-pixel antialiasing at 0%.
 l=base();l.celestial[2].z=M_PI/2;l.celestial[4].z=0;p=probe(ctx,enc,pipeline,l,YES,0,NO,NO);int hard=transitionPixels(p);free(p);
 l.celestial[4].z=1;p=probe(ctx,enc,pipeline,l,YES,0,NO,NO);require(transitionPixels(p)>hard*3,@"Softness does not widen the phase transition");free(p);
 // Background preservation includes nonzero grading lift and HDR. Check the production composition against a
 // vanilla-mode reference, then check partially lit pixels against an independent linear-alpha oracle.
 for(int hdr=0;hdr<2;hdr++)for(int srgb=0;srgb<2;srgb++)for(int lift=0;lift<2;lift++){
  background=(simd_float4){.12,.25,.4,1};l=base();l.materials[3].w=hdr;l.srgb=srgb;if(lift)l.grade[1]=(simd_float4){.15,.1,.2,0};
  l.celestial[3].z=0;float *sky=probe(ctx,enc,pipeline,l,YES,0,NO,YES);l.celestial[3].z=1;
  l.celestial[2].z=M_PI;p=probe(ctx,enc,pipeline,l,YES,0,NO,YES);require(!memcmp(sky,p,W*H*16),@"New moon is not exactly the sky background");free(p);
  l.celestial[2].z=-1;float *fullMoon=probe(ctx,enc,pipeline,l,YES,0,NO,YES);
  l.celestial[2].z=M_PI/2;l.celestial[4].z=.8;float *mask=probe(ctx,enc,pipeline,l,YES,0,NO,NO);
  p=probe(ctx,enc,pipeline,l,YES,0,NO,YES);int blends=0,transparent=0;
  for(int y=H/2-25;y<H/2+25;y++)for(int x=W/2-25;x<W/2+25;x++){
   int at=(y*W+x)*4;float alpha=mask[at+2]/2;
   if(alpha==0){transparent++;for(int c=0;c<3;c++)require(p[at+c]==sky[at+c],@"Shadowed moon pixel changed the background");}
   if(alpha>.01&&alpha<.99){blends++;for(int c=0;c<3;c++){
    double a=srgb?sky[at+c]:pow(sky[at+c],2.2),b=srgb?fullMoon[at+c]:pow(fullMoon[at+c],2.2),actual=srgb?p[at+c]:pow(p[at+c],2.2);
    require(fabs(actual-((1-alpha)*a+alpha*b))<2e-5,@"Moon transition is not linear-alpha blending with the actual sky");
   }}
  }
  require(blends>100&&transparent>100,@"Blend/transparent assertions never exercised");
  if(!hdr&&!srgb&&!lift)save(p,[@(argv[3]) stringByAppendingPathComponent:@"moon-soft-background.png"]);
  free(sky);free(fullMoon);free(mask);free(p);
 }
 background=(simd_float4){0,0,0,0};
 printf("PASS: %d GPU cases on %s: production sky integration, silhouettes, size/color/glow, eight phases, eight rotation angles on all shapes, adjustable penumbra, exact colored-sky transparency, independent linear-alpha oracle with grading lift/HDR/sRGB, terrain/fluid protection and disabled specialization\n",cases,name);
 return 0;
}}
