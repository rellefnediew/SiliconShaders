// The haze in the world's air on the GPU, through the production sunHaze: it must not follow the camera. Its amount along a view
// is the same from either end, level views get thinner higher up, looking down into denser air adds fog, a zero falloff is uniform.
#include "sun_test_common.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Haze lighting ABI");
static id<MTLRenderPipelineState> pipeline;static Ctx *ctx;static id<MTLCommandQueue> queue;static id<MTLTexture> target;
static double haze(float density,float falloff,float camera,float height,float distance){
 Lighting l={.enabled=1,.env={1,0,0,1}};l.p[16]=(simd_float4){density,falloff,1,3};l.aurora[2].w=camera;float u[2]={distance,height};
 id<MTLCommandBuffer> c=[queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
 id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:pipeline];[r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:u length:sizeof u atIndex:1];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[c commit];[c waitUntilCompleted];
 float out[4];[target getBytes:out bytesPerRow:16 fromRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0];require(isfinite(out[0]),@"Nonfinite haze");return out[0];
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3||argc==4,@"Usage: fog_height_check TERRAIN SUN [report-only]");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 haze_probe_fs(constant SunLighting &l[[buffer(0)]],constant float2 &u[[buffer(1)]]){return float4(sunHaze(u.x,u.y,l));}\n"];
 char name[256],err[16384];ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));queue=[ctx->device newCommandQueue];
 NSError *issue=nil;MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:o error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool off=false;[fc setConstantValue:&off type:MTLDataTypeBool atIndex:60];pd.fragmentFunction=[lib newFunctionWithName:@"haze_probe_fs" constantValues:fc error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:1 height:1 mipmapped:NO];d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;target=[ctx->device newTextureWithDescriptor:d];
 const float D=.007,K=.075;
 double up=haze(D,K,4,40,80),down=haze(D,K,44,-40,80),low=haze(D,K,2,0,100),high=haze(D,K,60,0,100),into=haze(D,K,60,-55,100),uniform1=haze(D,0,0,0,100),uniform2=haze(D,0,200,-50,100),belowSea=haze(D,K,0,-30,50),seaLevel=haze(D,K,0,0,50);
 printf("from below %.4f, from above %.4f; level view at 2 blocks %.4f, at 60 blocks %.4f; from 60 down to the ground %.4f; no falloff %.4f / %.4f; below sea level %.4f / at it %.4f\n",up,down,low,high,into,uniform1,uniform2,belowSea,seaLevel);
 if(argc==4)return 0;
 require(fabs(up-down)<1e-4,@"The haze along a view depends on which end is the camera: it follows the camera");
 require(low>high*1.5,@"A level view high up is not thinner than one at the ground");
 require(into>high*1.2,@"Looking down into denser air adds no fog");
 require(fabs(uniform1-uniform2)<1e-4,@"Without falloff the haze must be uniform");
 require(fabs(belowSea-seaLevel)<1e-4,@"The density grows without limit below sea level");
 printf("PASS: haze stays in the world's air: same from either end of a view, thinner high up, denser looking down, uniform without falloff, bounded below sea level\n");
}return 0;}
