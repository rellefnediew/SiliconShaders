#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdio.h>
#include <math.h>
typedef struct {simd_float4x4 inverse,previous;simd_float4 delta,config;} Temporal;
typedef struct {simd_float4x4 clip;simd_int4 cam;simd_float4 offset,sun,config;simd_uint4 counts;} Sun;
typedef struct {simd_float4x4 first,second;simd_float4 config,farConfig;simd_float4x4 extra[2];simd_float4 extraConfig[2];} Blend;
typedef struct {simd_float4 bounds[4],enabled,inverseSize;} Glass;
static void check(BOOL ok,NSString*message){if(!ok){fprintf(stderr,"FAIL %s\n",message.UTF8String);exit(1);}}
static double median(double*x,int n){double sorted[32];memcpy(sorted,x,n*sizeof(double));for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(sorted[i]>sorted[j]){double t=sorted[i];sorted[i]=sorted[j];sorted[j]=t;}return sorted[n/2];}
static id<MTLTexture> texture(id<MTLDevice>d,int w,int h,MTLPixelFormat format){MTLTextureDescriptor*t=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];t.storageMode=MTLStorageModePrivate;t.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;return[d newTextureWithDescriptor:t];}
int main(int argc,const char**argv){@autoreleasepool{
 check(argc>=4&&argc<=7,@"Usage: memory_cost_check TERRAIN SUN WIDTH [HEIGHT FORMAT BATCH]");int width=atoi(argv[3]),height=argc>4?atoi(argv[4]):width*9/16,batch=argc>6?atoi(argv[6]):1;const char*format=argc>5?argv[5]:"rgba8";BOOL hdr=!strcmp(format,"rgba16");MTLPixelFormat targetFormat=hdr?MTLPixelFormatRGBA16Float:!strcmp(format,"bgra8")?MTLPixelFormatBGRA8Unorm:!strcmp(format,"srgb")?MTLPixelFormatRGBA8Unorm_sRGB:MTLPixelFormatRGBA8Unorm;int bytes=hdr?8:4;check(width>0&&height>0&&batch>0,@"Invalid fixture arguments");id<MTLDevice>d=MTLCreateSystemDefaultDevice();id<MTLCommandQueue>queue=[d newCommandQueue];NSError*error=nil;

 NSString*extra=@"\nstruct BenchIn{float4 normal[[color(0)]];float4 history[[color(1)]];float depth[[depth(any)]];};\nfragment BenchIn bench_input_fs(float4 p[[position]],constant uint &mode[[buffer(0)]],constant SunTemporalFrame &frame[[buffer(1)]]){float dist=mode==1?120.f:8.f;float z=.05f/dist;float2 uv=p.xy/frame.config.xy;float4 h=frame.inverseView*float4(uv*2-1,z,1);float3 pos=h.xyz/h.w;float visibility=.5f+.5f*sin(p.x*.07f)*cos(p.y*.04f);float3 v=mode==2?float3(visibility,1-visibility,.25f+visibility*.5f):float3(visibility);float fog=mode==3&&uint(p.x/31)%3==0?-1.f:.125f;float historyDistance=length(pos)+(uint(p.x/29)%5==0?.8f:0);return {float4(v,fog),float4(clamp(v+.15f,0.f,1.f),historyDistance),z};}\nstruct BenchDepth{float depth[[depth(any)]];};\nfragment BenchDepth bench_shadow_fs(float4 p[[position]]){return {.5f};}\n";

 id<MTLLibrary>libs[2];id<MTLRenderPipelineState>mask[2];
 for(int variant=0;variant<2;variant++){
  NSString*terrain=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error];NSString*sunCode=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&error];MTLCompileOptions*opt=[MTLCompileOptions new];opt.languageVersion=MTLLanguageVersion3_0;opt.mathMode=MTLMathModeFast;
  libs[variant]=[d newLibraryWithSource:[[terrain stringByAppendingString:sunCode] stringByAppendingString:extra] options:opt error:&error];check(libs[variant]!=nil,error.description);
  MTLRenderPipelineDescriptor*p=[MTLRenderPipelineDescriptor new];p.vertexFunction=[libs[variant] newFunctionWithName:@"sun_screen_vs"];p.fragmentFunction=[libs[variant] newFunctionWithName:variant?@"sun_temporal_fetch_fs":@"sun_temporal_fs"];p.colorAttachments[0].pixelFormat=targetFormat;p.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA16Float;mask[variant]=[d newRenderPipelineStateWithDescriptor:p error:&error];check(mask[variant]!=nil,error.description);
 }
 MTLRenderPipelineDescriptor*ip=[MTLRenderPipelineDescriptor new];ip.vertexFunction=[libs[0] newFunctionWithName:@"sun_screen_vs"];ip.fragmentFunction=[libs[0] newFunctionWithName:@"bench_input_fs"];ip.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;ip.colorAttachments[1].pixelFormat=MTLPixelFormatRGBA16Float;ip.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;id<MTLRenderPipelineState>input=[d newRenderPipelineStateWithDescriptor:ip error:&error];check(input!=nil,error.description);
 MTLRenderPipelineDescriptor*sp=[MTLRenderPipelineDescriptor new];sp.vertexFunction=ip.vertexFunction;sp.fragmentFunction=[libs[0] newFunctionWithName:@"bench_shadow_fs"];sp.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;id<MTLRenderPipelineState>shadowPSO=[d newRenderPipelineStateWithDescriptor:sp error:&error];check(shadowPSO!=nil,error.description);
 MTLDepthStencilDescriptor*dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionAlways;id<MTLDepthStencilState>ds=[d newDepthStencilStateWithDescriptor:dd];
 id<MTLTexture>normals=texture(d,width,height,MTLPixelFormatRGBA16Float),depth=texture(d,width,height,MTLPixelFormatDepth32Float),outputs[2]={texture(d,width,height,targetFormat),texture(d,width,height,targetFormat)},maps[3];
 id<MTLTexture>inputHistory=texture(d,width,height,MTLPixelFormatRGBA16Float),historyOut[2]={texture(d,width,height,MTLPixelFormatRGBA16Float),texture(d,width,height,MTLPixelFormatRGBA16Float)};
 id<MTLTexture>sceneCopy=texture(d,width,height,targetFormat),depthCopy=texture(d,width,height,MTLPixelFormatDepth32Float);
 float fog[16]={.1f,.2f,.3f,1,1000,2000,1000,2000,1000,2000};
 float ranges[]={24,78.4f,256};Sun sun={.sun={.6,.8,0,1},.counts={0,0,4096,1}};Blend blend={.config={0,0,17,3}};Glass glass={0};
 for(int level=0;level<3;level++){
  int size=4096>>level;maps[level]=texture(d,size,size,MTLPixelFormatDepth32Float);float range=ranges[level],extent=fmaxf(128,2*range);simd_float4x4 clip={.columns={{-.8f/range,0,-.6f/(2*extent),0},{.6f/range,0,-.8f/(2*extent),0},{0,1.f/range,0,0},{0,0,.5,1}}};simd_float4 config={range,1.f/size,.00015f*128/fmaxf(128,2*range),1};
  if(level==0){sun.clip=clip;sun.config=config;}else if(level==2){blend.second=clip;blend.farConfig=config;}else{blend.extra[0]=clip;blend.extraConfig[0]=config;}
  glass.bounds[level]=(simd_float4){0,0,1,1};glass.inverseSize[level]=1.f/size;
  id<MTLCommandBuffer>cb=[queue commandBuffer];MTLRenderPassDescriptor*rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.depthAttachment.texture=maps[level];rp.depthAttachment.loadAction=MTLLoadActionDontCare;rp.depthAttachment.storeAction=MTLStoreActionStore;id<MTLRenderCommandEncoder>r=[cb renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:shadowPSO];[r setDepthStencilState:ds];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[cb commit];[cb waitUntilCompleted];check(cb.status==MTLCommandBufferStatusCompleted,cb.error.description);
 }
 MTLTextureDescriptor*ct=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:targetFormat width:width height:height mipmapped:NO];ct.storageMode=MTLStorageModeShared;ct.usage=MTLTextureUsageShaderRead;id<MTLTexture>color=[d newTextureWithDescriptor:ct];void*rgba=malloc((size_t)width*height*bytes);
 for(int y=0;y<height;y++)for(int x=0;x<width;x++){float values[4]={.2f+.6f*x/MAX(1,width-1),.1f+.7f*y/MAX(1,height-1),.3f+.1f*((x/31)%2),.4f+.5f*(x%17)/16};for(int c=0;c<4;c++){int at=(y*width+x)*4+c;if(hdr)((__fp16*)rgba)[at]=values[c];else((uint8_t*)rgba)[at]=(uint8_t)lrintf(values[c]*255);}}
 [color replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:rgba bytesPerRow:width*bytes];free(rgba);

 MTLSamplerDescriptor*sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterLinear;sd.compareFunction=MTLCompareFunctionLessEqual;sd.sAddressMode=sd.tAddressMode=MTLSamplerAddressModeClampToEdge;id<MTLSamplerState>sampler=[d newSamplerStateWithDescriptor:sd];
 float focal=1.3f,near=.05f;simd_float4x4 projection={.columns={{focal/(16.f/9),0,0,0},{0,focal,0,0},{0,0,0,-1},{0,0,near,0}}};simd_float4x4 down={.columns={{1,0,0,0},{0,0,1,0},{0,-1,0,0},{0,0,0,1}}};Temporal frame={.inverse=simd_inverse(simd_mul(projection,down)),.previous=simd_mul(projection,down),.config={width,height,.1,1}};
 printf("device=%s size=%dx%d format=%s batch=%d\n",d.name.UTF8String,width,height,format,batch);
 for(uint mode=0;mode<4;mode++){
  glass.enabled=mode==3?(simd_float4){1,1,1,0}:(simd_float4){0};
  id<MTLCommandBuffer>cb=[queue commandBuffer];MTLRenderPassDescriptor*rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=normals;rp.colorAttachments[1].texture=inputHistory;rp.colorAttachments[1].loadAction=MTLLoadActionDontCare;rp.colorAttachments[1].storeAction=MTLStoreActionStore;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;rp.colorAttachments[0].storeAction=MTLStoreActionStore;rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionDontCare;rp.depthAttachment.storeAction=MTLStoreActionStore;id<MTLRenderCommandEncoder>r=[cb renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:input];[r setDepthStencilState:ds];[r setFragmentBytes:&mode length:4 atIndex:0];[r setFragmentBytes:&frame length:sizeof frame atIndex:1];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[cb commit];[cb waitUntilCompleted];
  double times[2][16];
  for(int repeat=0;repeat<20;repeat++)for(int turn=0;turn<2;turn++){
   int variant=(turn+repeat)&1;id<MTLCommandBuffer>cmd=[queue commandBuffer];for(int step=0;step<batch;step++){
   id<MTLBlitCommandEncoder>initial=[cmd blitCommandEncoder];[initial copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,height,1) toTexture:outputs[variant] destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
   if(!variant){[initial copyFromTexture:outputs[variant] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,height,1) toTexture:sceneCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[initial copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,height,1) toTexture:depthCopy destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];}[initial endEncoding];MTLRenderPassDescriptor*pass=[MTLRenderPassDescriptor renderPassDescriptor];pass.colorAttachments[0].texture=outputs[variant];pass.colorAttachments[1].texture=historyOut[variant];pass.colorAttachments[1].loadAction=MTLLoadActionDontCare;pass.colorAttachments[1].storeAction=MTLStoreActionStore;pass.colorAttachments[0].loadAction=variant?MTLLoadActionLoad:MTLLoadActionDontCare;pass.colorAttachments[0].storeAction=MTLStoreActionStore;id<MTLRenderCommandEncoder>rr=[cmd renderCommandEncoderWithDescriptor:pass];[rr setRenderPipelineState:mask[variant]];[rr setFragmentBytes:&frame length:sizeof frame atIndex:0];[rr setFragmentBytes:&sun length:sizeof sun atIndex:1];[rr setFragmentBytes:&blend length:sizeof blend atIndex:2];[rr setFragmentBytes:&glass length:sizeof glass atIndex:3];[rr setFragmentBytes:fog length:sizeof fog atIndex:24];[rr setFragmentTexture:variant?nil:sceneCopy atIndex:0];[rr setFragmentTexture:normals atIndex:1];[rr setFragmentTexture:inputHistory atIndex:2];[rr setFragmentTexture:variant?depth:depthCopy atIndex:3];[rr drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[rr endEncoding];}[cmd commit];[cmd waitUntilCompleted];check(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.description);if(repeat>=4)times[variant][repeat-4]=(cmd.GPUEndTime-cmd.GPUStartTime)*1000/batch;
  }
  NSUInteger rowBytes=((width*bytes+255)/256)*256,historyRow=((width*8+255)/256)*256;
  id<MTLBuffer>read[2],historyRead[2];for(int i=0;i<2;i++){
   read[i]=[d newBufferWithLength:rowBytes*height options:MTLResourceStorageModeShared];historyRead[i]=[d newBufferWithLength:historyRow*height options:MTLResourceStorageModeShared];id<MTLCommandBuffer>copy=[queue commandBuffer];id<MTLBlitCommandEncoder>b=[copy blitCommandEncoder];
   [b copyFromTexture:outputs[i] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,height,1) toBuffer:read[i] destinationOffset:0 destinationBytesPerRow:rowBytes destinationBytesPerImage:rowBytes*height];
   [b copyFromTexture:historyOut[i] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,height,1) toBuffer:historyRead[i] destinationOffset:0 destinationBytesPerRow:historyRow destinationBytesPerImage:historyRow*height];
   [b endEncoding];[copy commit];[copy waitUntilCompleted];check(copy.status==MTLCommandBufferStatusCompleted,copy.error.description);
  }
  float largest=0;unsigned changed=0,historyChanged=0;
  for(int y=0;y<height;y++){
   uint8_t*a=(uint8_t*)read[0].contents+y*rowBytes,*b=(uint8_t*)read[1].contents+y*rowBytes;
   for(int x=0;x<width*4;x++){float av=hdr?((__fp16*)a)[x]:a[x]/255.f,bv=hdr?((__fp16*)b)[x]:b[x]/255.f,diff=fabsf(av-bv);if(diff>0)changed++;largest=fmaxf(largest,diff);}
   uint8_t*ha=(uint8_t*)historyRead[0].contents+y*historyRow,*hb=(uint8_t*)historyRead[1].contents+y*historyRow;
   for(int x=0;x<width*8;x++)historyChanged+=ha[x]!=hb[x];
  }
  printf("mode=%u oldMs=%.4f newMs=%.4f maxDifference=%.8f changedChannels=%u historyChanged=%u\n",mode,median(times[0],16),median(times[1],16),largest,changed,historyChanged);fflush(stdout);
  check(largest<=1.f/255,@"Color attachment input changed visible output");check(historyChanged==0,@"Color attachment input changed shadow history");
  for(int i=0;i<2;i++){[read[i] release];[historyRead[i] release];}

 }
 puts("PASS: temporal near/far/colored/sky-edge color parity");
}return 0;}
