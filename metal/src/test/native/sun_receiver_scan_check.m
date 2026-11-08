#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
typedef struct {simd_float4x4 inverse,view,clips[4];simd_float4 guards;simd_uint4 config;} Frame;
static void check(BOOL ok,NSString*why){if(!ok){fprintf(stderr,"FAIL: %s\n",why.UTF8String);exit(1);}}
static double median(double*v,int n){double a[32];memcpy(a,v,n*sizeof *a);for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(a[j]<a[i]){double t=a[i];a[i]=a[j];a[j]=t;}return a[n/2];}
int main(int argc,const char**argv){@autoreleasepool{
 check(argc>=3&&argc<=8,@"Usage: scan_check TERRAIN SUN [width height resolution levels] [stress|batch]");int width=argc>3?atoi(argv[3]):1920,height=argc>4?atoi(argv[4]):1200,resolution=argc>5?atoi(argv[5]):4096,levels=argc>6?atoi(argv[6]):4;
 BOOL stressOnly=argc==8&&!strcmp(argv[7],"stress");int batch=argc==8&&!strcmp(argv[7],"batch")?8:1;
 check(width>0&&height>0&&levels>=2&&levels<=4,@"Invalid fixture dimensions");
 id<MTLDevice>d=MTLCreateSystemDefaultDevice();check(d!=nil,@"No Metal device");id<MTLCommandQueue>queue=[d newCommandQueue];NSError*error=nil;
 NSString*source=[[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error] stringByAppendingString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&error]];
 source=[source stringByAppendingString:@"\nstruct ScanOut {float4 normal[[color(0)]];float depth[[depth(any)]];};\nfragment ScanOut scan_input_fs(float4 p[[position]],constant uint &mode[[buffer(0)]]){uint2 pixel=uint2(p.xy);float distance=16.f;float fog=0.f;if(mode==1)distance=(pixel.x&1)?180.f:2.5f;else if(mode==2)distance=8.f+.04f*p.y+.012f*p.x;else if(mode==3)fog=p.y<400.f?-1.f:0.f;else if(mode==4)fog=-1.f;else if(mode==5)fog=(pixel.x%23==0&&pixel.y%17==0)?0.f:-1.f;else if(mode==8)distance=240.f;float depth=.05f/distance;if(mode==6&&pixel.x==0&&pixel.y==0)depth=0.f;if(mode==7)depth=0.f;return {float4(0,1,0,fog),depth};}\n"];
 MTLCompileOptions*options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;options.mathMode=MTLMathModeFast;
 id<MTLLibrary>lib=[d newLibraryWithSource:source options:options error:&error];check(lib!=nil,error.description);
 const char*names[]={"sun_receivers_mark","sun_receivers_mark_optimized"};id<MTLComputePipelineState>mark[2];
 for(int i=0;i<2;i++){id<MTLFunction>f=[lib newFunctionWithName:[NSString stringWithUTF8String:names[i]]];check(f!=nil,@"Missing marker variant");mark[i]=[d newComputePipelineStateWithFunction:f error:&error];check(mark[i]!=nil,error.description);[f release];}

 MTLRenderPipelineDescriptor*pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"scan_input_fs"];pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;id<MTLRenderPipelineState>render=[d newRenderPipelineStateWithDescriptor:pd error:&error];check(render!=nil,error.description);
 MTLDepthStencilDescriptor*dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionAlways;id<MTLDepthStencilState>ds=[d newDepthStencilStateWithDescriptor:dd];
 MTLTextureDescriptor*td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:width height:height mipmapped:NO];td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture>normals=[d newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture>depth=[d newTextureWithDescriptor:td];
 id<MTLBuffer>bins[2];for(int i=0;i<2;i++)bins[i]=[d newBufferWithLength:4*4097*4 options:MTLResourceStorageModeShared];
 float focal=1.3f,near=.05f;simd_float4x4 projection={.columns={{focal/((float)width/height),0,0,0},{0,focal,0,0},{0,0,0,-1},{0,0,near,0}}};
 simd_float4x4 down={.columns={{1,0,0,0},{0,0,1,0},{0,-1,0,0},{0,0,0,1}}};Frame f={.view=simd_mul(projection,down),.config={width,height,levels,0}};f.inverse=simd_inverse(f.view);
 for(int i=0;i<levels;i++){float range=levels==2?(i?256:24):levels==3?(i==0?24:i==1?78.4f:256):(i==0?24:i==1?52.8f:i==2?116.3f:256),size=MAX(256,resolution>>i);f.clips[i]=(simd_float4x4){.columns={{-.8f/range,0,-.6f/512,0},{.6f/range,0,-.8f/512,0},{0,1.f/range,0,0},{0,0,.5,1}}};f.guards[i]=12/size+.05f/range;}
 const char*labels[]={"flat","near-far","slope","partial-sky","sky","thin-surfaces","invalid-pixel","invalid-all","far"};
 printf("device=%s size=%dx%d resolution=%d levels=%d batch=%d\n",d.name.UTF8String,width,height,resolution,levels,batch);fflush(stdout);
 for(uint mode=0;mode<(stressOnly?0:9);mode++){
  id<MTLCommandBuffer>input=[queue commandBuffer];MTLRenderPassDescriptor*rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=normals;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;rp.colorAttachments[0].storeAction=MTLStoreActionStore;rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionDontCare;rp.depthAttachment.storeAction=MTLStoreActionStore;
  id<MTLRenderCommandEncoder>r=[input renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:render];[r setDepthStencilState:ds];[r setFragmentBytes:&mode length:4 atIndex:0];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[input commit];[input waitUntilCompleted];check(input.status==MTLCommandBufferStatusCompleted,input.error.description);
  double times[2][16];
  for(int repeat=0;repeat<20;repeat++)for(int turn=0;turn<2;turn++){
   int variant=(turn+repeat)%2;id<MTLCommandBuffer>cmd=[queue commandBuffer];for(int pass=0;pass<batch;pass++){id<MTLBlitCommandEncoder>b=[cmd blitCommandEncoder];[b fillBuffer:bins[variant] range:NSMakeRange(0,bins[variant].length) value:0];[b endEncoding];id<MTLComputeCommandEncoder>c=[cmd computeCommandEncoder];
   [c setComputePipelineState:mark[variant]];[c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:bins[variant] offset:0 atIndex:1];[c setTexture:depth atIndex:0];[c setTexture:normals atIndex:1];[c dispatchThreads:MTLSizeMake(width,height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[c endEncoding];}[cmd commit];[cmd waitUntilCompleted];check(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.description);
   if(repeat>=4)times[variant][repeat-4]=(cmd.GPUEndTime-cmd.GPUStartTime)*1000/batch;
  }
  for(int i=1;i<2;i++){
   uint32_t*a=bins[0].contents,*b=bins[i].contents;uint32_t differences=0;for(uint32_t k=0;k<4*4097;k++)differences+=a[k]!=b[k];
   if(differences){printf("mode=%s variant=%d differences=%u\n",labels[mode],i,differences);fflush(stdout);}
   check(differences==0,@"Receiver coverage/depth differs from reference");
  }
  printf("mode=%s referenceMs=%.4f optimizedMs=%.4f identical=1\n",labels[mode],median(times[0],16),median(times[1],16));fflush(stdout);
 }
 // Queue changed inputs, resets and scans without an intermediate GPU wait.
 // Each snapshot must still match the corresponding reference after retirement.
 id<MTLBuffer> snapshots[18];id<MTLCommandBuffer> pending[18];
 for(int i=0;i<18;i++) {
  uint mode=(uint)i/2;int variant=i%2;
  snapshots[i]=[d newBufferWithLength:bins[variant].length options:MTLResourceStorageModeShared];
  pending[i]=[queue commandBuffer];MTLRenderPassDescriptor*rp=[MTLRenderPassDescriptor renderPassDescriptor];
  rp.colorAttachments[0].texture=normals;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
  rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionDontCare;rp.depthAttachment.storeAction=MTLStoreActionStore;
  id<MTLRenderCommandEncoder>r=[pending[i] renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:render];[r setDepthStencilState:ds];[r setFragmentBytes:&mode length:4 atIndex:0];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];
  id<MTLBlitCommandEncoder>b=[pending[i] blitCommandEncoder];[b fillBuffer:bins[variant] range:NSMakeRange(0,bins[variant].length) value:0];[b endEncoding];
  id<MTLComputeCommandEncoder>c=[pending[i] computeCommandEncoder];[c setComputePipelineState:mark[variant]];[c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:bins[variant] offset:0 atIndex:1];[c setTexture:depth atIndex:0];[c setTexture:normals atIndex:1];[c dispatchThreads:MTLSizeMake(width,height,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[c endEncoding];
  b=[pending[i] blitCommandEncoder];[b copyFromBuffer:bins[variant] sourceOffset:0 toBuffer:snapshots[i] destinationOffset:0 size:bins[variant].length];[b endEncoding];[pending[i] commit];
 }
 [pending[17] waitUntilCompleted];
 for(int i=0;i<18;i++)check(pending[i].status==MTLCommandBufferStatusCompleted,pending[i].error.description);
 for(int i=0;i<18;i+=2)check(!memcmp(snapshots[i].contents,snapshots[i+1].contents,snapshots[i].length),@"Queued frame reset/read coherence changed receiver values");
 puts("PASS: 18 queued changed-input frames retain exact grid/depth values without intermediate waits");
 puts("PASS: all receiver cells and maximum depths match reference bit for bit");
}return 0;}
