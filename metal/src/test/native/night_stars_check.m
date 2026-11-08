// The End's stars in the Overworld's clear night, through the production sky pixel on the GPU: nothing while their value is 0 or
// the sky effects are specialized out, none below the horizon, stronger with the setting, the End's own star field, turning with
// the sun without a jump when the moon takes over as the light. No Minecraft window. Optional third argument: a PNG of the sky.
#include "sun_test_common.h"
#import <ImageIO/ImageIO.h>
#import <CoreGraphics/CoreGraphics.h>
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Sky lighting ABI");
typedef struct {simd_float4x4 inverse,previous;simd_float4 delta,config;} Frame;
enum {W=640,H=400};
static id<MTLCommandQueue> queue;static id<MTLTexture> target,noise;
static Lighting night(void){
 Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,1}};
 l.grade[0]=(simd_float4){1,1,1,1};l.grade[2]=(simd_float4){1,1,1,0};l.grade[3]=(simd_float4){1,1,1,1};
 l.p[10]=(simd_float4){.22f,.44f,.96f,1};l.p[11]=(simd_float4){.64f,.8f,1,1};
 l.p[12]=(simd_float4){.16f,.22f,.5f,.06f};l.p[13]=(simd_float4){.3f,.38f,.62f,.1f};l.p[14]=(simd_float4){1,.46f,.22f,1};
 l.p[15]=(simd_float4){1,.5f,1,0};return l;
}
// A camera at pitch radians above the horizon, looking along +x: clip space to a world ray, as sunSkyDirection reads it.
static Frame camera(float pitch){
 float c=cosf(pitch),s=sinf(pitch),ax=.9f,ay=-.9f*H/W; // Metal's pixel rows run downward, and uv maps straight to clip space here.
 simd_float3 forward={c,s,0},up={-s,c,0},right={0,0,1};
 Frame f={.config={W,H,0,0}};
 f.inverse=simd_matrix((simd_float4){right.x*ax,right.y*ax,right.z*ax,0},(simd_float4){up.x*ay,up.y*ay,up.z*ay,0},(simd_float4){0,0,0,1},(simd_float4){forward.x,forward.y,forward.z,0});
 return f;
}
static float *render(id<MTLRenderPipelineState> p,Lighting l,Frame f,simd_float3 towardLight){
 Sun sun={.sun={towardLight.x,towardLight.y,towardLight.z,0}};float fog[16]={.02f,.03f,.06f,1,0,512,0,512,512,512};
 id<MTLCommandBuffer> c=[queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
 id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:p];[r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:&sun length:sizeof sun atIndex:1];[r setFragmentBytes:&f length:sizeof f atIndex:2];[r setFragmentBytes:fog length:sizeof fog atIndex:3];[r setFragmentTexture:noise atIndex:0];
 [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[c commit];[c waitUntilCompleted];
 float *px=malloc(W*H*16);[target getBytes:px bytesPerRow:W*16 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
 for(int i=0;i<W*H*4;i++)require(isfinite(px[i]),@"Nonfinite sky");return px;
}
static double change(const float *a,const float *b){double sum=0;for(int i=0;i<W*H*4;i++)if(i%4!=3)sum+=fabsf(a[i]-b[i]);return sum;}
static int spots(const float *a,const float *b){int n=0;for(int i=0;i<W*H;i++)if(fabsf(a[i*4+1]-b[i*4+1])>.02f)n++;return n;}
static void save(const float *px,const char *path){
 uint8_t *rgba=malloc(W*H*4);for(int i=0;i<W*H*4;i++)rgba[i]=i%4==3?255:(uint8_t)(fminf(fmaxf(px[i],0),1)*255+.5f);
 CGColorSpaceRef cs=CGColorSpaceCreateDeviceRGB();CGContextRef ctx=CGBitmapContextCreate(rgba,W,H,8,W*4,cs,(CGBitmapInfo)kCGImageAlphaNoneSkipLast);CGImageRef img=CGBitmapContextCreateImage(ctx);
 CGImageDestinationRef out=CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:@(path)],CFSTR("public.png"),1,NULL);CGImageDestinationAddImage(out,img,NULL);require(CGImageDestinationFinalize(out),@"PNG not written");free(rgba);
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3||argc==4,@"Usage: night_stars_check TERRAIN SUN [OUT.png]");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 stars_sky_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant SunTemporalFrame &frame[[buffer(2)]],constant Fog &fog[[buffer(3)]],texture2d<float> noise[[texture(0)]]){return float4(sunSkyPixel(float3(.02,.03,.06),p.xy/frame.config.xy,0.0,frame,sun,fog,l,noise,noise),1);}\n"
  "fragment float4 stars_only_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant SunTemporalFrame &frame[[buffer(2)]]){float2 uv=p.xy/frame.config.xy;float3 d=sunSkyDirection(uv,frame);return float4(sunNightStars(d,length(sunSkyDirection(uv+float2(0,1.0/frame.config.y),frame)-d),sun,l),1);}\n"
  "fragment float4 stars_end_fs(float4 p[[position]],constant SunTemporalFrame &frame[[buffer(2)]]){float2 uv=p.xy/frame.config.xy;float3 d=sunSkyDirection(uv,frame);return float4(sunEndStars(d,length(sunSkyDirection(uv+float2(0,1.0/frame.config.y),frame)-d)),1);}\n"];
 char name[256],err[16384];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));queue=[ctx->device newCommandQueue];noise=white(ctx);
 NSError *issue=nil;MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:o error:&issue];require(lib!=nil,issue.localizedDescription);
 id<MTLRenderPipelineState> (^build)(NSString *,int)=^id<MTLRenderPipelineState>(NSString *fn,int fx){
  MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool on=fx!=0;[fc setConstantValue:&on type:MTLDataTypeBool atIndex:60];NSError *e=nil;
  MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:fn constantValues:fc error:&e];require(pd.fragmentFunction!=nil,e.localizedDescription);
  pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;id<MTLRenderPipelineState> p=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];require(p!=nil,e.localizedDescription);return p;};
 id<MTLRenderPipelineState> skyOn=build(@"stars_sky_fs",1),skyOff=build(@"stars_sky_fs",0),only=build(@"stars_only_fs",1),endStars=build(@"stars_end_fs",1);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:W height:H mipmapped:NO];d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;target=[ctx->device newTextureWithDescriptor:d];
 simd_float3 midnight={0,-1,0};Frame high=camera(.7f);
 // Off: the sky without them is the same picture to the last bit. In the game rain and day send this 0 (SunAtmosphereChecks).
 Lighting dark=night(),lit=night();lit.sky[1].w=1;float *none=render(skyOn,dark,high,midnight),*stars=render(skyOn,lit,high,midnight);
 int count=spots(none,stars);printf("stars in view: %d pixels stand out, %.1f total\n",count,change(none,stars));
 require(count>60&&count<W*H/20,@"Stars: not a sparse field of points");
 float *off=render(skyOff,lit,high,midnight);require(change(off,none)==0,@"Stars drawn with the sky effects specialized out");free(off);
 Lighting brighter=night();brighter.sky[1].w=2.5f;float *strong=render(skyOn,brighter,high,midnight);require(change(none,strong)>change(none,stars)*1.3,@"The setting does not brighten the stars");free(strong);
 if(argc==4)save(stars,argv[3]);
 // At the horizon: nothing under it, and they fade in rather than end on a line.
 Frame level=camera(0);float *lowNone=render(skyOn,dark,level,midnight),*lowStars=render(skyOn,lit,level,midnight);double lower=0,upper=0;
 for(int y=0;y<H;y++)for(int x=0;x<W;x++){double e=fabsf(lowNone[(y*W+x)*4+1]-lowStars[(y*W+x)*4+1]);if(y>=H/2)lower+=e;else upper+=e;}
 require(lower==0&&upper>0,@"Stars below the horizon");free(lowNone);free(lowStars);
 // The field is the End's, seen at the moment the sun's rotation is zero (light straight up): above the horizon air, equal.
 Frame zenith=camera(1.45f);float *same=render(only,lit,zenith,(simd_float3){0,1,0}),*end=render(endStars,lit,zenith,midnight);
 double field=0,energy=0;for(int i=0;i<W*H*4;i++)if(i%4!=3){field+=fabsf(same[i]-end[i]);energy+=end[i];}
 require(energy>1&&field<energy*.001,@"Not the End's star field");free(same);free(end);
 // They turn with the sun: turning sky and camera together about the sun's axis leaves the stars where they were.
 float angle=.5f;Frame a=camera(1.0f),b=camera(1.0f+angle);float *before=render(only,lit,a,(simd_float3){0,-1,0}),*after=render(only,lit,b,(simd_float3){sinf(angle),-cosf(angle),0});
 double moved=change(before,after),total=0;for(int i=0;i<W*H*4;i++)if(i%4!=3)total+=before[i];printf("turning with the sun: %.3f of %.1f differs\n",moved,total);
 require(total>1&&moved<total*.02,@"Stars do not turn with the sun");
 float *still=render(only,lit,a,(simd_float3){sinf(angle),-cosf(angle),0});require(change(before,still)>total*.5,@"Stars stand still while the sun moves");free(still);free(before);free(after);
 // The moon taking over as the light (sun.sun flips, moon set): not a star moves.
 Lighting moon=lit;moon.moon=1;float *byMoon=render(skyOn,moon,high,(simd_float3){-midnight.x,-midnight.y,-midnight.z}),*plain=render(only,lit,high,midnight),*plainMoon=render(only,moon,high,(simd_float3){-midnight.x,-midnight.y,-midnight.z});
 require(change(plain,plainMoon)==0,@"Stars jump when the moon becomes the light");free(byMoon);free(plain);free(plainMoon);
 free(none);free(stars);
 printf("PASS: night stars: exact sky without them and when specialized out, sparse points, brighter with the setting, none under the horizon, the End's field, turning with the sun, no jump at the moon's turn\n");
}return 0;}
