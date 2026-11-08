// Sky next to terrain, on the GPU: sun and moon are only shaded in sky pixels, so a leaf beside a sky pixel leaves it without valid
// screen derivatives. Rendered as a checkerboard of sky and terrain, no pixel away from the disc may take on the disc's colour.
#include "sun_test_common.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
typedef struct {simd_float4x4 inverseView,previousView;simd_float4 cameraDelta,config;} Temporal;
_Static_assert(sizeof(Lighting)==864,"Celestial lighting ABI");
static const int W=256,H=256;
static NSString *const PROBE=@"\nfragment float4 edge_probe_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant SunTemporalFrame &frame[[buffer(2)]],constant uint &checker[[buffer(3)]]){float2 uv=p.xy/frame.config.xy;if(checker!=0u&&((uint(p.x)+uint(p.y))&1u)==0u)return float4(-1.0);float3 d=sunSkyDirection(uv,frame);SunCelestialLayers b=sunCelestialLayers(d,sunSkyPixelSize(uv,d,frame),sun,l);return float4(b.solar.coverage,b.lunar.coverage,0,1);}\n";
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3||argc==4,@"Usage: celestial_edge_check TERRAIN SUN [report-only]");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];source=[source stringByAppendingString:PROBE];
 char name[256],err[16384];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));NSError *issue=nil;MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:o error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];pd.fragmentFunction=[lib newFunctionWithName:@"edge_probe_fs" constantValues:fc error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;id<MTLRenderPipelineState> pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:W height:H mipmapped:NO];d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;id<MTLTexture> target=[ctx->device newTextureWithDescriptor:d];id<MTLCommandQueue> queue=[ctx->device newCommandQueue];
 simd_float3 center=simd_normalize((simd_float3){-.6,.8,0}),right=simd_normalize(simd_cross((simd_float3){0,0,1},center)),up=simd_cross(center,right);
 Temporal t={.config={W,H,0,0}};t.inverseView.columns[0]=(simd_float4){right.x*.14,right.y*.14,right.z*.14,0};t.inverseView.columns[1]=(simd_float4){up.x*.14,up.y*.14,up.z*.14,0};t.inverseView.columns[2]=(simd_float4){0,0,0,1};t.inverseView.columns[3]=(simd_float4){center.x,center.y,center.z,0};
 double worst=0;int bad=0,edge=0;
 for(int moon=0;moon<2;moon++)for(int checker=0;checker<2;checker++){
  Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,1}};l.celestial[0]=(simd_float4){4,.8,.2,.0523598776};l.celestial[1]=(simd_float4){.3,.7,2,.0523598776};l.celestial[2]=(simd_float4){0,0,-1,0};l.celestial[3]=(simd_float4){0,.2,1,0};l.celestial[4]=(simd_float4){1,0,.25,1};
  simd_float3 v=moon?-center:center;Sun sun={.sun={v.x,v.y,v.z,1}};uint32_t mode=checker;
  id<MTLCommandBuffer> c=[queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:pipeline];[r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:&sun length:sizeof sun atIndex:1];[r setFragmentBytes:&t length:sizeof t atIndex:2];[r setFragmentBytes:&mode length:4 atIndex:3];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[c commit];[c waitUntilCompleted];
  float *px=malloc(W*H*16);[target getBytes:px bytesPerRow:W*16 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];
  double radius=.0523598776/.14*W*.5,caseWorst=0;int caseBad=0,soft=0;
  for(int y=0;y<H;y++)for(int x=0;x<W;x++){float *q=px+(y*W+x)*4;if(q[0]<0)continue;double cover=moon?q[1]:q[0],dist=hypot(x+.5-W*.5,y+.5-H*.5);require(isfinite(cover),@"Nonfinite coverage");
   if(dist>radius+3){if(cover>caseWorst)caseWorst=cover;if(cover>.01)caseBad++;}
   if(dist<radius-3&&cover<.99)caseBad++;
   if(cover>.01&&cover<.99)soft++;}
  free(px);printf("%s, %s: pixels away from the disc with its colour %d, strongest %.3f, soft edge pixels %d\n",moon?"moon":"sun",checker?"sky beside terrain":"open sky",caseBad,caseWorst,soft);
  if(caseWorst>worst)worst=caseWorst;bad+=caseBad;if(!checker)edge=soft>edge?soft:edge;
 }
 if(argc==4)return 0;
 require(bad==0,@"Sky pixels beside terrain take on the disc's colour");require(edge>20,@"The disc's edge lost its smoothing");
 printf("PASS: sun and moon stay on their discs in sky beside terrain, with a smooth edge in open sky\n");
}return 0;}
