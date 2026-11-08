#include "sun_test_common.h"
#include "mcsun_surface.h"
typedef struct { simd_float4x4 inverse,view,clips[4];simd_float4 guards;simd_uint4 config; } Receiver;
typedef struct { uint64_t handles[4];simd_float4x4 model;simd_uint4 layout;simd_int4 draw;simd_float4 config,lo,hi; } Actor;
typedef struct { uint32_t count;float radius;uint32_t pad[2];simd_float4 planes[16]; } Cull;
static uint32_t bound(int x,int y,int z) { return (x+16)|((y+16)<<8)|((z+16)<<16); }
int main(int argc,const char **argv) { @autoreleasepool {
 require(argc==3,@"Usage: sun_receiver_grid_check TERRAIN SUN");
 NSString *source=[[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil]
   stringByAppendingString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:nil]];
 char error[4096]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
 MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;options.mathMode=MTLMathModeFast;
 NSError *issue=nil;id<MTLLibrary> library=[ctx->device newLibraryWithSource:source options:options error:&issue];require(library!=nil,issue.description);
 id<MTLComputePipelineState> pipeline[4];NSArray *names=@[@"sun_receivers_reset",@"sun_receivers_mark",@"sun_receivers_transparent",@"sun_cull_actors"];
 for(int i=0;i<4;i++){id<MTLFunction> f=[library newFunctionWithName:names[i]];pipeline[i]=[ctx->device newComputePipelineStateWithFunction:f error:&issue];[f release];require(pipeline[i]!=nil,issue.description);}
 id<MTLBuffer> bins=[ctx->device newBufferWithLength:4*4097*4 options:MTLResourceStorageModeShared],stats=[ctx->device newBufferWithLength:128 options:MTLResourceStorageModeShared];
 MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:2 height:2 mipmapped:NO];td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
 td.pixelFormat=MTLPixelFormatRGBA32Float;td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;id<MTLTexture> normals=[ctx->device newTextureWithDescriptor:td];
 float normalData[16]={0,1,0,0, 0,0,0,-1, 0,0,0,-1, 0,0,0,-1};[normals replaceRegion:MTLRegionMake2D(0,0,2,2) mipmapLevel:0 withBytes:normalData bytesPerRow:32];
 Section section={.count=1};Record record={.meta=2u<<9,.lo=bound(0,0,0),.hi=bound(0,0,0)};uint32_t candidate=0;
 id<MTLBuffer> sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,&record,sizeof record),candidates=buffer(ctx,&candidate,4);
 Actor actors[4]={0};simd_float4 positions[]={{.25,.25,.1,0},{.25,.25,.9,0},{0,0,.1,0},{-.5,-.5,.1,0}};
 for(int i=0;i<4;i++){actors[i].draw=(simd_int4){4,0,0,0};actors[i].config=(simd_float4){0,1,0,0};actors[i].lo=actors[i].hi=positions[i];}
 actors[3].config.y=0;actors[3].draw=(simd_int4){3,2,-2,0};
 id<MTLBuffer> actorData=buffer(ctx,actors,sizeof actors),args=[ctx->device newBufferWithLength:4*32 options:MTLResourceStorageModeShared];
 Receiver f={.inverse=matrix_identity_float4x4,.view=matrix_identity_float4x4,.clips={matrix_identity_float4x4,matrix_identity_float4x4},.config={2,2,2,1}};
 Sun sun={.clip=matrix_identity_float4x4,.offset={.25,.25,.25,0},.sections=1,.capacity=1,.on=5};Cull cull={0};OwnSunSurfaceCull surface={.enabled=1,.candidateCount=UINT32_MAX,.grid={0,0,1,0}};uint32_t count=4;
 for(int test=0;test<2;test++) {
  if(test)f.inverse.columns[3].w=0;
  id<MTLCommandBuffer> command=[ctx->queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor new];rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionClear;rp.depthAttachment.storeAction=MTLStoreActionStore;rp.depthAttachment.clearDepth=.5;
  id<MTLRenderCommandEncoder> clear=[command renderCommandEncoderWithDescriptor:rp];[clear endEncoding];[rp release];
  id<MTLComputeCommandEncoder> c=[command computeCommandEncoderWithDispatchType:MTLDispatchTypeSerial];
  [c setComputePipelineState:pipeline[0]];[c setBuffer:bins offset:0 atIndex:0];[c setBuffer:stats offset:0 atIndex:1];[c dispatchThreads:MTLSizeMake(4*4097,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
  [c setComputePipelineState:pipeline[1]];[c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:bins offset:0 atIndex:1];[c setTexture:depth atIndex:0];[c setTexture:normals atIndex:1];[c dispatchThreads:MTLSizeMake(2,2,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
  [c setComputePipelineState:pipeline[2]];[c setBytes:&sun length:sizeof sun atIndex:2];[c setBuffer:sections offset:0 atIndex:3];[c setBuffer:recs offset:0 atIndex:4];[c setBuffer:candidates offset:0 atIndex:5];[c dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
  [c setComputePipelineState:pipeline[3]];[c setBytes:&sun length:sizeof sun atIndex:0];[c setBytes:&cull length:sizeof cull atIndex:5];[c setBuffer:bins offset:0 atIndex:6];[c setBytes:&surface length:sizeof surface atIndex:7];[c setBuffer:stats offset:0 atIndex:9];[c setBuffer:actorData offset:0 atIndex:10];[c setBytes:&count length:4 atIndex:11];[c setBuffer:args offset:0 atIndex:12];[c dispatchThreads:MTLSizeMake(4,1,1) threadsPerThreadgroup:MTLSizeMake(4,1,1)];[c endEncoding];
  [command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
  uint32_t *b=bins.contents,*a=args.contents,*v=stats.contents;
  if(!test) {
   require(b[0]==0&&b[4097]==0,@"Sky disabled the receiver grid");
   require(b[1+16*64+16] && b[1+40*64+40] && !b[1+32*64+32],@"Opaque/transparent receivers were merged into one large box");
   require(a[0]==6 && a[8]==0 && a[16]==0 && a[24]==3 && a[27]==(uint32_t)-2,@"Actor receiver/depth culling or indexed draw ABI failed");
   require(v[5]==4&&v[6]==2,@"Actor telemetry incorrect");
  } else require(b[0]==1&&b[4097]==1&&a[8]==6&&a[16]==6,@"Invalid receiver reconstruction did not fail open");
 }
 puts("PASS GPU: disjoint opaque/transparent receiver tiles, sky skipping, light-depth rejection, actor and indexed indirect arguments, invalid-depth fallback");
 }return 0;
}
