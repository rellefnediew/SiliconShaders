#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdio.h>
typedef struct {simd_float4x4 inverse,view,clips[4];simd_float4 guards;simd_uint4 config;} Frame;
typedef struct {simd_float4x4 clip;simd_int4 cam;simd_float4 offset,sun,config;simd_uint4 counts;} Sun;
typedef struct {int x,y,z,time;uint32_t start,count,p0,p1;} Section;
typedef struct {uint32_t start,slot,meta,plane,lo,hi,p0,p1;} Rec;
typedef struct {simd_float4 lo,hi;} Box;
static void check(BOOL ok,NSString* message){if(!ok){fprintf(stderr,"FAIL %s\n",message.UTF8String);exit(1);}}
int main(int argc,const char**argv){@autoreleasepool{
 check(argc==3,@"Usage: translucent_bench TERRAIN SUN");NSError*error=nil;id<MTLDevice>d=MTLCreateSystemDefaultDevice();id<MTLCommandQueue>queue=[d newCommandQueue];
 NSString*source=[[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error] stringByAppendingString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&error]];
 MTLCompileOptions*options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;options.mathMode=MTLMathModeFast;id<MTLLibrary>lib=[d newLibraryWithSource:source options:options error:&error];check(lib!=nil,error.description);
 id<MTLComputePipelineState>pipe[2];NSArray*names=@[@"sun_receivers_transparent",@"sun_receivers_transparent_bounds"];
 for(int i=0;i<2;i++){id<MTLFunction>f=[lib newFunctionWithName:names[i]];pipe[i]=[d newComputePipelineStateWithFunction:f error:&error];check(pipe[i]!=nil,error.description);[f release];}
 enum{MESHES=16,PER_MESH=32};Section sections[MESHES]={0};Rec records[MESHES*PER_MESH]={0};Box boxes[MESHES]={0};uint32_t candidates[MESHES];
 for(int i=0;i<MESHES;i++){
  int x=(i%4)*16-32,z=(i/4)*16-32;sections[i]=(Section){.x=x,.z=z,.start=i*PER_MESH,.count=PER_MESH};candidates[i]=i;
  boxes[i]=(Box){.lo={x,-8,z,0},.hi={x+16,-8,z+16,0}};
  for(int j=0;j<PER_MESH;j++)records[i*PER_MESH+j]=(Rec){.slot=i,.meta=(2u<<9)|63u,.lo=0,.hi=0x00ffffffu};
 }
 id<MTLBuffer>sectionBuf=[d newBufferWithBytes:sections length:sizeof sections options:MTLResourceStorageModeShared],recBuf=[d newBufferWithBytes:records length:sizeof records options:MTLResourceStorageModeShared],candidateBuf=[d newBufferWithBytes:candidates length:sizeof candidates options:MTLResourceStorageModeShared],boxBuf=[d newBufferWithBytes:boxes length:sizeof boxes options:MTLResourceStorageModeShared];
 id<MTLBuffer>bins=[d newBufferWithLength:4*4097*4 options:MTLResourceStorageModeShared];
 Frame f={.inverse=matrix_identity_float4x4,.view={.columns={{1.f/128,0,0,0},{0,1.f/128,0,0},{0,0,1.f/256,0},{0,0,.5,1}}},.config={1920,1080,3,MESHES}};
 for(int i=0;i<3;i++){float range=i==0?24:i==1?78.4f:256,depth=fmaxf(128,2*range),resolution=4096>>i;f.clips[i]=(simd_float4x4){.columns={{-.8f/range,0,-.6f/(2*depth),0},{.6f/range,0,-.8f/(2*depth),0},{0,1.f/range,0,0},{0,0,.5,1}}};f.guards[i]=12/resolution+.05f/range;}
 Sun sun={.cam={0,8,0,0},.counts={MESHES,MESHES*PER_MESH,4096,5}};
 printf("%s: %d meshes, %d records, real 16-block water surfaces versus record sentinel [-16,239]\n",d.name.UTF8String,MESHES,MESHES*PER_MESH);
 for(int mode=0;mode<2;mode++) {
  double times[4];for(int repeat=0;repeat<4;repeat++) {
   id<MTLCommandBuffer>cb=[queue commandBuffer];id<MTLBlitCommandEncoder>b=[cb blitCommandEncoder];[b fillBuffer:bins range:NSMakeRange(0,bins.length) value:0];[b endEncoding];
   id<MTLComputeCommandEncoder>c=[cb computeCommandEncoder];[c setComputePipelineState:pipe[mode]];[c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:bins offset:0 atIndex:1];
   if(mode)[c setBuffer:boxBuf offset:0 atIndex:2];else{[c setBytes:&sun length:sizeof sun atIndex:2];[c setBuffer:sectionBuf offset:0 atIndex:3];[c setBuffer:recBuf offset:0 atIndex:4];[c setBuffer:candidateBuf offset:0 atIndex:5];}
   [c dispatchThreads:MTLSizeMake(MESHES,1,1) threadsPerThreadgroup:MTLSizeMake(MESHES,1,1)];[c endEncoding];[cb commit];[cb waitUntilCompleted];check(cb.status==MTLCommandBufferStatusCompleted,cb.error.description);times[repeat]=(cb.GPUEndTime-cb.GPUStartTime)*1000;
  }
  printf("%s gpuMs %.3f %.3f %.3f %.3f\n",mode?"actual mesh bounds":"old sentinel per record",times[0],times[1],times[2],times[3]);fflush(stdout);
  const uint32_t*grid=bins.contents;unsigned checked=0,occupied=0;
  for(int level=0;level<3;level++){check(grid[level*4097]==0,@"Unexpected grid fallback");for(int i=0;i<4096;i++)occupied+=grid[level*4097+1+i]!=0;}
  for(int mesh=0;mesh<MESHES;mesh++)for(int x=0;x<=16;x++)for(int z=0;z<=16;z++)for(int level=0;level<3;level++) {
   simd_float4 p=boxes[mesh].lo;p.x+=x;p.z+=z;p.w=1;simd_float3 q=simd_mul(f.clips[level],p).xyz;float guard=f.guards[level];
   if(q.x-guard>1||q.y-guard>1||q.x+guard< -1||q.y+guard< -1||q.z>1||q.z+2*guard+.001<0)continue;
   int x0=MAX(0,MIN(63,(int)floorf(((q.x-guard)*.5f+.5f)*64))),x1=MAX(0,MIN(63,(int)floorf(((q.x+guard)*.5f+.5f)*64)));
   int y0=MAX(0,MIN(63,(int)floorf(((q.y-guard)*.5f+.5f)*64))),y1=MAX(0,MIN(63,(int)floorf(((q.y+guard)*.5f+.5f)*64)));
   for(int yy=y0;yy<=y1;yy++)for(int xx=x0;xx<=x1;xx++){uint32_t bits=grid[level*4097+1+yy*64+xx];float value;memcpy(&value,&bits,4);check(bits&&value+1e-5>=fminf(1,q.z+2*guard+.001),@"Actual water receiver/filter footprint missing");checked++;}
  }
  printf("PASS %u true-water cell/depth checks, %u occupied cells\n",checked,occupied);fflush(stdout);
 }
}return 0;}
