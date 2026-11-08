// The night and after-rain sky on the GPU, through the production sunAtmosphere, sunNebula and their noises: nothing while off, each
// effect only where it belongs, its controls reach the picture, and the passes split without changing the sum. No Minecraft window.
#include "sun_test_common.h"
#include "mcaurora_noise.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Sky lighting ABI");
static const int W=320,H=200;
static id<MTLRenderPipelineState> pipeline;static Ctx *ctx;static id<MTLCommandQueue> queue;static id<MTLTexture> target,auroraNoise,skyNoise;
static Lighting base(void){
 Lighting l={.enabled=1,.env={1,0,0,1}};l.p[18].x=400;l.p[8].w=40.3;
 l.aurora[0]=(simd_float4){0,.08,-1,1};l.aurora[1]=(simd_float4){1,1.414,0,1};l.aurora[2]=(simd_float4){10,.1,2,0};
 l.sky[0]=(simd_float4){.375,1,.75,0};l.sky[1]=(simd_float4){.583,.183,1,0};l.sky[2]=(simd_float4){.01,.04,.35,1};l.sky[3]=(simd_float4){.32,.16,.58,1};l.p[7].x=-1;return l;
}
static float *render(Lighting l,simd_float3 light,float pitch,uint32_t parts){
 Sun sun={.sun={light.x,light.y,light.z,1}};float u[2]={pitch,parts};
 id<MTLCommandBuffer> c=[queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
 id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:pipeline];[r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:&sun length:sizeof sun atIndex:1];[r setFragmentBytes:u length:8 atIndex:2];[r setFragmentTexture:auroraNoise atIndex:0];[r setFragmentTexture:skyNoise atIndex:1];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[c commit];[c waitUntilCompleted];
 float *px=malloc(W*H*16);[target getBytes:px bytesPerRow:W*16 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];for(int i=0;i<W*H*4;i++)require(isfinite(px[i])&&px[i]>=-1,@"Nonfinite sky");return px;
}
static double energy(const float *p,int c){double e=0;for(int i=0;i<W*H;i++)e+=c<0?p[i*4]+p[i*4+1]+p[i*4+2]:p[i*4+c];return e;}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3,@"Usage: sky_fx_check TERRAIN SUN");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 sky_probe_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant float2 &u[[buffer(2)]],texture2d<float> aurora[[texture(0)]],texture2d<float> skyNoise[[texture(1)]]){float2 s=(p.xy-float2(160,100))/100.0*float2(.7,-.7);float c=cos(u.x),n=sin(u.x);float3 d=normalize(float3(c,n,0)+float3(-n,c,0)*s.y+float3(0,0,1)*s.x);uint parts=uint(u.y);float3 color=sunAtmosphere(d,p.xy,.01,sun,l,aurora,parts);if(parts&1u)color+=sunNebula(d,sun,l,skyNoise);return float4(color,d.y);}\n"];
 char name[256],err[16384];ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));queue=[ctx->device newCommandQueue];
 NSError *issue=nil;MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:o error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"sky_probe_fs" constantValues:[MTLFunctionConstantValues new] error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:W height:H mipmapped:NO];d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;target=[ctx->device newTextureWithDescriptor:d];
 uint8_t *bytes=malloc(MC_SKY_NOISE_SIZE*MC_SKY_NOISE_SIZE*4);mc_sky_noise_fill(bytes);
 // Seamless: the nebula noise's opposite edges must meet like its neighbouring rows do.
 double seam=0,inner=0;for(int x=0;x<MC_SKY_NOISE_SIZE;x++){seam+=abs(bytes[x*4]-bytes[((MC_SKY_NOISE_SIZE-1)*MC_SKY_NOISE_SIZE+x)*4]);inner+=abs(bytes[(100*MC_SKY_NOISE_SIZE+x)*4]-bytes[(101*MC_SKY_NOISE_SIZE+x)*4]);}
 require(seam<inner*2.5+MC_SKY_NOISE_SIZE,@"The baked sky noise is not seamless");
 MTLTextureDescriptor *nd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:MC_SKY_NOISE_SIZE height:MC_SKY_NOISE_SIZE mipmapped:YES];nd.storageMode=MTLStorageModeShared;skyNoise=[ctx->device newTextureWithDescriptor:nd];for(int level=0,side=MC_SKY_NOISE_SIZE;side>0;level++,side/=2){[skyNoise replaceRegion:MTLRegionMake2D(0,0,side,side) mipmapLevel:level withBytes:bytes bytesPerRow:side*4];if(side>1)mc_sky_noise_downsample(bytes,side);}free(bytes);
 uint8_t small[128*128*4];mc_aurora_noise_fill(small);nd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:YES];nd.storageMode=MTLStorageModeShared;auroraNoise=[ctx->device newTextureWithDescriptor:nd];for(int level=0,side=128;side>0;level++,side/=2){[auroraNoise replaceRegion:MTLRegionMake2D(0,0,side,side) mipmapLevel:level withBytes:small bytesPerRow:side*4];if(side>1)mc_aurora_noise_downsample(small,side);}
 simd_float3 night=simd_normalize((simd_float3){1,-.55,.1}),day=simd_normalize((simd_float3){-1,.28,.05});
 Lighting l=base();float *p=render(l,night,.6,3);require(energy(p,-1)==0,@"Everything off still draws");free(p);
 // Aurora
 l=base();l.p[6].w=1;p=render(l,night,.6,3);double aurora=energy(p,-1);require(aurora>1,@"No aurora");for(int i=0;i<W*H;i++)if(p[i*4+3]<0)require(p[i*4]==0&&p[i*4+1]==0&&p[i*4+2]==0,@"Aurora below the horizon");
 Lighting v=l;v.sky[0]=(simd_float4){1,0,0,0};v.sky[1]=(simd_float4){1,0,0,0};float *q=render(v,night,.6,3);require(energy(q,1)==0&&energy(q,2)==0&&energy(q,0)>0,@"Aurora colors do not reach the picture");free(q);
 v=l;v.aurora[1].w=.2;q=render(v,night,.6,3);require(energy(q,-1)<aurora*.8,@"Aurora count does not thin the sky");free(q);
 v=l;v.p[6].w=-1;q=render(v,night,.6,3);require(energy(q,-1)>0&&fabs(energy(q,-1)-aurora)>0,@"Blocky aurora lost");free(q);
 v=l;v.aurora[0].y=0;q=render(v,night,.6,3);require(energy(q,-1)>0,@"Flat aurora lost");free(q);
 free(p);
 // Nebula
 l=base();l.p[7].x=1;p=render(l,night,.7,3);double nebula=energy(p,-1);require(nebula>1,@"No nebula");
 q=render(l,night,.7,2);require(energy(q,-1)==0,@"Nebula outside the half-resolution pass");free(q);
 v=l;v.sky[2]=(simd_float4){0,1,0,1};v.sky[3]=(simd_float4){0,1,0,1};q=render(v,night,.7,3);require(energy(q,0)==0&&energy(q,2)==0&&energy(q,1)>0,@"Nebula colors do not reach the picture");free(q);
 v=l;v.sky[2].w=2;q=render(v,night,.7,3);double change=0;for(int i=0;i<W*H*4;i++)if(i%4!=3)change+=fabs(q[i]-p[i]);require(change>nebula*.05,@"Nebula size does nothing");free(q);
 v=l;v.sky[3].w=0;v.p[18].x=900;Lighting w=v;w.p[18].x=400;q=render(v,night,.7,3);float *r=render(w,night,.7,3);require(energy(q,-1)==energy(r,-1),@"Nebula moves at zero speed");free(q);free(r);
 v=l;v.p[18].x=9000;q=render(v,night,.7,3);require(fabs(energy(q,-1)-nebula)>0,@"Nebula never moves");free(q);free(p);
 // The half-resolution pass and the sky pixels add up to the whole.
 l=base();l.p[6].w=1;l.p[7].x=1;p=render(l,night,.6,3);q=render(l,night,.6,1);float *s=render(l,night,.6,2);for(int i=0;i<W*H*4;i++)if(i%4!=3)require(fabs(p[i]-q[i]-s[i])<1e-5,@"Passes do not add up");free(p);free(q);free(s);
 // Rainbow: only in its band opposite the sun.
 l=base();l.p[7].y=1;p=render(l,day,.12,3);require(energy(p,-1)>0,@"No rainbow");
 for(int i=0;i<W*H;i++){float px=i%W+.5f,py=i/W+.5f;float sx=(px-160)/100*.7f,sy=-(py-100)/100*.7f;float c=cos(.12f),n=sin(.12f);simd_float3 dir=simd_normalize((simd_float3){c-n*sy,n+c*sy,sx});float opposite=-simd_dot(dir,day);
  if(opposite<.68f||opposite>.76f)require(p[i*4]==0&&p[i*4+1]==0&&p[i*4+2]==0,@"Rainbow outside its band");}
 free(p);
 printf("PASS: sky effects: nothing while off, aurora above the horizon with colors, count and both styles; nebula with colors, size and speed; passes add up; rainbow in its band; seamless nebula noise\n");
}return 0;}
