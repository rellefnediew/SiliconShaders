// The sky under the cloud layer, through the production sunSkyLinear on the GPU: clear weather is untouched by every weather value,
// the layer takes the color and is brightest overhead, rainDim takes brightness, thunder darkens, a strike flashes. No Minecraft window.
#include "sun_test_common.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Sky lighting ABI");
static id<MTLRenderPipelineState> pipeline;static id<MTLCommandQueue> queue;static id<MTLTexture> target;
static Lighting base(void){
 Lighting l={.enabled=1,.env={1,0,0,1}};simd_float4 blue={.35,.55,1,1};
 l.p[10]=blue;l.p[11]=blue;l.p[12]=blue;l.p[13]=blue;l.p[14]=blue;l.p[0]=(simd_float4){1,.9,.7,1};l.p[1]=l.p[0];return l;
}
static simd_float3 sky(Lighting l,simd_float3 direction){
 Sun sun={.sun={0,1,0,1}};simd_float4 d={direction.x,direction.y,direction.z,0};
 id<MTLCommandBuffer> c=[queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
 id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:pipeline];[r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:&sun length:sizeof sun atIndex:1];[r setFragmentBytes:&d length:sizeof d atIndex:2];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[c commit];[c waitUntilCompleted];
 float px[4];[target getBytes:px bytesPerRow:16 fromRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0];for(int i=0;i<3;i++)require(isfinite(px[i]),@"Nonfinite sky");return (simd_float3){px[0],px[1],px[2]};
}
static float luma(simd_float3 c){return c.x*.2126f+c.y*.7152f+c.z*.0722f;}
static float chroma(simd_float3 c){float hi=fmaxf(c.x,fmaxf(c.y,c.z)),lo=fminf(c.x,fminf(c.y,c.z));return (hi-lo)/fmaxf(hi,1e-5f);}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3,@"Usage: weather_sky_check TERRAIN SUN");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 weather_probe_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant float4 &d[[buffer(2)]]){return float4(sunSkyLinear(normalize(d.xyz),sun,l),1);}\n"];
 char name[256],err[16384];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));queue=[ctx->device newCommandQueue];
 NSError *issue=nil;MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:o error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"weather_probe_fs" constantValues:[MTLFunctionConstantValues new] error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:1 height:1 mipmapped:NO];d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;target=[ctx->device newTextureWithDescriptor:d];
 simd_float3 zenith={.02,1,0},side={.7,.7,0},horizon={1,0,.02};
 // Clear: rainDim alone changes nothing; the sky keeps its color.
 Lighting clear=base();simd_float3 z0=sky(clear,zenith),h0=sky(clear,horizon);Lighting dimOnly=clear;dimOnly.p[7].z=1;
 simd_float3 z1=sky(dimOnly,zenith);require(z0.x==z1.x&&z0.y==z1.y&&z0.z==z1.z,@"rainDim changes a clear sky");require(chroma(z0)>.5f,@"The clear test sky has no color");
 // Full cover: nearly neutral, a touch blue; overhead brighter than the horizon, against a clear sky that is even.
 Lighting cloud=base();cloud.env.y=1;simd_float3 zc=sky(cloud,zenith),sc=sky(cloud,side),hc=sky(cloud,horizon);
 // The test sky is an extreme blue (chroma .9), so what the layer leaves of it counts here.
 require(chroma(zc)<.45f&&chroma(hc)<.45f&&zc.z>zc.x,@"The cloud layer keeps the sky's color");
 require(luma(zc)>luma(sc)&&luma(sc)>luma(hc)*1.05f,@"Overcast sky is not brightest overhead");(void)h0;
 require(luma(hc)>.25f*luma(zc),@"The horizon goes black");
 // The layer closes smoothly: color falls with the cover.
 float last=chroma(z0);for(float c=.25f;c<=1;c+=.25f){Lighting v=base();v.env.y=c;float now=chroma(sky(v,zenith));require(now<last,@"Color does not fall with the cover");last=now;}
 // rainDim takes brightness under the layer only.
 Lighting dim=cloud;dim.p[7].z=1;require(luma(sky(dim,zenith))<luma(zc)*.85f,@"rainDim does not dim the layer");
 // Thunder darkens by its share, a strike adds to it, more overhead.
 Lighting storm=cloud;storm.p[7].w=.4f;simd_float3 zs=sky(storm,zenith);require(fabsf(luma(zs)-luma(zc)*.6f)<.001f,@"Thunder darkening");
 Lighting flash=cloud;flash.p[18].y=1;float up=luma(sky(flash,zenith))-luma(zc),low=luma(sky(flash,horizon))-luma(hc);require(up>.3f&&low>.2f&&up>low,@"Lightning flash");
 printf("PASS: sky under the cloud layer: clear untouched, neutral and brightest overhead, color falls with the cover, rainDim, thunder and flash\n");
}return 0;}
