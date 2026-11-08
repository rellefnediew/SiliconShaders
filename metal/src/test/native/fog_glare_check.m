// The sun's and moon's glow in fog on the GPU, through the production sunFog: looking down toward a low sun or moon there must
// be no wedge (the flattened fog direction once carried the full glare through the ground); a hill in thin fog must not show
// the sun behind it; and where fog is thick enough to stand in for the sky, far away at the horizon, the glow stays.
#include "sun_test_common.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
typedef struct {float color[4],start,end,renderStart,renderEnd,skyEnd,cloudEnd;} Fog;
_Static_assert(sizeof(Lighting)==864,"Fog glare lighting ABI");
static const int W=129,H=3;
static Lighting lighting(void){
 Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,1}};
 l.grade[0]=(simd_float4){1,1,1,1};l.grade[2]=(simd_float4){1,1,1,0};l.grade[3]=(simd_float4){1,1,1,1};
 l.p[0]=(simd_float4){1,.85,.58,2.4};l.p[1]=(simd_float4){1,.55,.28,2.2};
 l.p[10]=(simd_float4){.22,.44,.96,1};l.p[11]=(simd_float4){.64,.8,1,1};l.p[12]=(simd_float4){.16,.22,.5,.06};l.p[13]=(simd_float4){.3,.38,.62,.1};
 l.p[14]=(simd_float4){1,.46,.22,1};l.p[15]=(simd_float4){.9,1.75,1,1};l.p[16]=(simd_float4){.007,0,1,1};return l;
}
static float *probe(Ctx *ctx,id<MTLRenderPipelineState> pipeline,simd_float3 light){
 Lighting l=lighting();Sun sun={.sun={light.x,light.y,light.z,1}};Fog fog={.end=1024,.renderEnd=1024,.skyEnd=1024,.cloudEnd=1024};
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:W height:H mipmapped:NO];
 d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;id<MTLTexture> target=[ctx->device newTextureWithDescriptor:d];
 id<MTLCommandQueue> queue=[ctx->device newCommandQueue];id<MTLCommandBuffer> command=[queue commandBuffer];
 MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
 id<MTLRenderCommandEncoder> r=[command renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:pipeline];
 [r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:&sun length:sizeof sun atIndex:1];[r setFragmentBytes:&fog length:sizeof fog atIndex:3];
 [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[command commit];[command waitUntilCompleted];
 float *pixels=malloc(W*H*16);[target getBytes:pixels bytesPerRow:W*16 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];return pixels;
}
static double luma(const float *p,int x,int y){const float *c=p+(y*W+x)*4;return .2126*c[0]+.7152*c[1]+.0722*c[2];}
// How warm the fog is: a colour fan toward the sun changes this even where brightness stays the same.
static double warmth(const float *p,int x,int y){const float *c=p+(y*W+x)*4;return c[0]/fmax(c[2],1e-4);}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3||argc==4,@"Usage: fog_glare_check TERRAIN SUN [report-only]");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 fog_glare_probe_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant Fog &fog[[buffer(3)]]){float az=(p.x-64.5)/64.0*0.7,pitch=p.y<1.0?-0.84:0.02,reach=p.y<1.0?30.0:p.y<2.0?1000.0:100.0;float3 d=float3(cos(pitch)*cos(az),sin(pitch),cos(pitch)*sin(az));float3 q=float3(l.pad0,l.pad1,l.hand[2].w)+d*reach;SunFog a=sunFog(0.0,q,1.0,fog,sun,l);return float4(a.radiance,a.transmittance);}\n"];
 char name[256],err[16384];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));
 NSError *issue=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;
 id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:options error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];
 MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool off=false;[fc setConstantValue:&off type:MTLDataTypeBool atIndex:60];
 pd.fragmentFunction=[lib newFunctionWithName:@"fog_glare_probe_fs" constantValues:fc error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);
 pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;id<MTLRenderPipelineState> pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 BOOL report=argc==4,wedge=NO,flat=NO,through=NO,fan=NO;
 struct {const char *name;simd_float3 light;} cases[2]={{"low sun",simd_normalize((simd_float3){1,.03,0})},{"low moon",simd_normalize((simd_float3){-1,-.03,0})}};
 for(int i=0;i<2;i++){
  float *p=probe(ctx,pipeline,cases[i].light);
  for(int x=0;x<W*H*4;x++)require(isfinite(p[x]),@"Nonfinite fog");
  double downPeak=luma(p,64,0),downSide=luma(p,64+64,0),upPeak=luma(p,64,1),upSide=luma(p,64+64,1),hillPeak=luma(p,64,2),hillSide=luma(p,64+64,2);
  double fanDown=warmth(p,64,0)/warmth(p,64+64,0),fanHill=warmth(p,64,2)/warmth(p,64+64,2),fanFar=warmth(p,64,1)/warmth(p,64+64,1);
  double wedgeRatio=downPeak/fmax(downSide,1e-6),glowRatio=upPeak/fmax(upSide,1e-6),hillRatio=hillPeak/fmax(hillSide,1e-6);free(p);
  printf("%s: toward it / 40 deg beside it: looking down %.4f; hill 100 blocks away %.4f; horizon 1000 blocks away %.4f; warmth down %.3f, hill %.3f, far %.3f\n",cases[i].name,wedgeRatio,hillRatio,glowRatio,fanDown,fanHill,fanFar);
  if(wedgeRatio>1.03)wedge=YES;
  if(glowRatio<1.05)flat=YES;
  if(hillRatio>1.1)through=YES;
  if(fanDown>1.1||fanHill>1.1)fan=YES;
 }
 if(report)return 0;
 require(!wedge,@"Sun or moon glow makes a wedge in fog looking down");require(!flat,@"Sun or moon glow vanished from fog at the horizon");require(!through,@"Sun or moon glow shines through a hill in thin fog");require(!fan,@"Thin fog takes on the sunset colour in a fan toward the sun");
 printf("PASS: no glow wedge looking down at a low sun or moon, none through a hill in thin fog, no sunset colour fan in thin fog; the glow stays in thick fog at the horizon\n");
}return 0;}
