#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdio.h>
typedef struct {simd_float4x4 inverse,view,clips[4];simd_float4 guards;simd_uint4 config;} Frame;
static void check(BOOL ok,NSString *message){if(!ok){fprintf(stderr,"%s\n",message.UTF8String);exit(1);}}
int main(int argc,const char **argv){@autoreleasepool{
 check(argc==4,@"Usage: receiver_bench TERRAIN SUN WIDTH");int width=atoi(argv[3]),height=width*9/16;
 id<MTLDevice>d=MTLCreateSystemDefaultDevice();id<MTLCommandQueue>queue=[d newCommandQueue];NSError*error=nil;
 NSString*code=[[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error] stringByAppendingString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&error]];
 code=[code stringByAppendingString:@"\nvertex float4 bench_vs(uint i[[vertex_id]]){float2 p=float2((i<<1)&2,i&2);return float4(p*2-1,0,1);}\nstruct BenchOut{float4 normal[[color(0)]];float depth[[depth(any)]];};\nfragment BenchOut bench_fs(float4 p[[position]],constant uint &mode[[buffer(0)]]){return {float4(0,1,0,0),(mode && (uint(p.x)&1))?.001f:.9f};}\n"];
 MTLCompileOptions*opt=[MTLCompileOptions new];opt.languageVersion=MTLLanguageVersion3_0;opt.mathMode=MTLMathModeFast;
 id<MTLLibrary>lib=[d newLibraryWithSource:code options:opt error:&error];check(lib!=nil,error.description);
 id<MTLFunction>fn=[lib newFunctionWithName:@"sun_receivers_mark"];id<MTLComputePipelineState>mark=[d newComputePipelineStateWithFunction:fn error:&error];check(mark!=nil,error.description);
 MTLRenderPipelineDescriptor*pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"bench_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"bench_fs"];pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;id<MTLRenderPipelineState>render=[d newRenderPipelineStateWithDescriptor:pd error:&error];check(render!=nil,error.description);
 MTLDepthStencilDescriptor*dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionAlways;id<MTLDepthStencilState>ds=[d newDepthStencilStateWithDescriptor:dd];
 MTLTextureDescriptor*td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:width height:height mipmapped:NO];td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture>normal=[d newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture>depth=[d newTextureWithDescriptor:td];
 id<MTLBuffer>bins=[d newBufferWithLength:4*4097*4 options:MTLResourceStorageModePrivate];
 float focal=1.3f,near=.05f;simd_float4x4 projection={.columns={{focal/(16.f/9),0,0,0},{0,focal,0,0},{0,0,0,-1},{0,0,near,0}}};
 simd_float4x4 down={.columns={{1,0,0,0},{0,0,1,0},{0,-1,0,0},{0,0,0,1}}};Frame f={.view=simd_mul(projection,down),.config={width,height,3,0}};f.inverse=simd_inverse(f.view);
 for(int i=0;i<3;i++){float range=i==0?24:i==1?78.4f:256;float resolution=4096>>i;f.clips[i]=(simd_float4x4){.columns={{1/range,0,0,0},{0,1/range,0,0},{0,0,1.f/512,0},{0,0,.5,1}}};f.guards[i]=12/resolution+.05f/range;}
 printf("device=%s size=%dx%d\n",d.name.UTF8String,width,height);
 for(uint mode=0;mode<2;mode++){
  id<MTLCommandBuffer>init=[queue commandBuffer];MTLRenderPassDescriptor*rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=normal;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;rp.colorAttachments[0].storeAction=MTLStoreActionStore;rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionDontCare;rp.depthAttachment.storeAction=MTLStoreActionStore;
  id<MTLRenderCommandEncoder>r=[init renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:render];[r setDepthStencilState:ds];[r setFragmentBytes:&mode length:4 atIndex:0];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[init commit];[init waitUntilCompleted];check(init.status==MTLCommandBufferStatusCompleted,init.error.description);
  double times[4];for(int repeat=0;repeat<4;repeat++){
   id<MTLCommandBuffer>cb=[queue commandBuffer];id<MTLBlitCommandEncoder>b=[cb blitCommandEncoder];[b fillBuffer:bins range:NSMakeRange(0,bins.length) value:0];[b endEncoding];id<MTLComputeCommandEncoder>c=[cb computeCommandEncoder];[c setComputePipelineState:mark];[c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:bins offset:0 atIndex:1];[c setTexture:depth atIndex:0];[c setTexture:normal atIndex:1];[c dispatchThreads:MTLSizeMake(width,height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[c endEncoding];[cb commit];[cb waitUntilCompleted];check(cb.status==MTLCommandBufferStatusCompleted,cb.error.description);times[repeat]=(cb.GPUEndTime-cb.GPUStartTime)*1000;
  }
  id<MTLBuffer>read=[d newBufferWithLength:bins.length options:MTLResourceStorageModeShared];id<MTLCommandBuffer>copy=[queue commandBuffer];id<MTLBlitCommandEncoder>blit=[copy blitCommandEncoder];[blit copyFromBuffer:bins sourceOffset:0 toBuffer:read destinationOffset:0 size:bins.length];[blit endEncoding];[copy commit];[copy waitUntilCompleted];check(copy.status==MTLCommandBufferStatusCompleted,copy.error.description);
  const uint32_t *grid=read.contents;unsigned long checked=0;
  for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
   float z=(mode && (x&1))?.001f:.9f;simd_float4 h=simd_mul(f.inverse,(simd_float4){2*(x+.5f)/width-1,2*(y+.5f)/height-1,z,1});simd_float3 p=h.xyz/h.w;
   for(int level=0;level<3;level++) {
    check(!grid[level*4097],@"Receiver grid failed open unexpectedly");
    simd_float3 q=simd_mul(f.clips[level],(simd_float4){p.x,p.y,p.z,1}).xyz;
    float guard=f.guards[level],required=fminf(1,fmaxf(1e-8,q.z+2*guard+.001f));
    if(q.x-guard>1||q.y-guard>1||q.x+guard< -1||q.y+guard< -1||q.z>1||q.z+2*guard+.001<0)continue;
    int x0=MAX(0,MIN(63,(int)floorf(((q.x-guard)*.5f+.5f)*64))),x1=MAX(0,MIN(63,(int)floorf(((q.x+guard)*.5f+.5f)*64)));
    int y0=MAX(0,MIN(63,(int)floorf(((q.y-guard)*.5f+.5f)*64))),y1=MAX(0,MIN(63,(int)floorf(((q.y+guard)*.5f+.5f)*64)));
    for(int yy=y0;yy<=y1;yy++)for(int xx=x0;xx<=x1;xx++) {
     uint32_t bits=grid[level*4097+1+yy*64+xx];float value;memcpy(&value,&bits,4);
     check(bits && value+1e-5>=required,@"A real receiver/filter footprint was dropped");checked++;
    }
   }
  }
  printf("PASS receiver coverage: %lu cell/depth checks\n",checked);[read release];
  printf("mode=%s gpuMs=%.3f,%.3f,%.3f,%.3f\n",mode?"near-far edges":"flat",times[0],times[1],times[2],times[3]);fflush(stdout);
 }
}return 0;}
