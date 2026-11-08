#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
typedef struct {int x,y,z,upload;uint32_t start,count,pad[2];} Section;
typedef struct {uint32_t start,slot,meta;int plane;uint32_t lo,hi,pad[2];} Record;
typedef struct {simd_float4x4 clip;simd_int4 cam;simd_float4 offset,sun,config;uint32_t sections,capacity,resolution,on;} Sun;
typedef struct {uint32_t count;float nearRadius;uint32_t pad[2];simd_float4 planes[16];} Cull;
typedef struct {uint32_t enabled;float guard,nearRadius;uint32_t candidates;simd_uint4 grid;} Surface;
static void check(BOOL ok,NSString *why){if(!ok){fprintf(stderr,"FAIL: %s\n",why.UTF8String);exit(1);}}
static int compare(const void*a,const void*b){uint32_t x=*(const uint32_t*)a,y=*(const uint32_t*)b;return (x>y)-(x<y);}
static uint32_t pack(int x,int y,int z){return (x+16)|((y+16)<<8)|((z+16)<<16);}
static uint32_t bits(float x){uint32_t u;memcpy(&u,&x,4);return u;}
static double median(double*v,int n){double a[32];memcpy(a,v,n*sizeof *a);for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(a[j]<a[i]){double t=a[i];a[i]=a[j];a[j]=t;}return a[n/2];}
int main(int argc,const char**argv){@autoreleasepool{
 check(argc==3,@"Usage: selection_check TERRAIN SUN");NSError*error=nil;id<MTLDevice>d=MTLCreateSystemDefaultDevice();check(d!=nil,@"No Metal device");id<MTLCommandQueue>queue=[d newCommandQueue];
 NSString*terrain=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error];NSString*sun=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&error];
 MTLCompileOptions*options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;options.mathMode=MTLMathModeFast;
 id<MTLLibrary>library=[d newLibraryWithSource:[terrain stringByAppendingString:sun] options:options error:&error];check(library!=nil,error.description);
 const char*names[]={"sun_reset","sun_reset_exact","sun_cull","sun_cull_exact","sun_reset_select","sun_reset_select_exact","sun_select_sections","sun_cull_selected","sun_cull_selected_exact","sun_select_sections_exact"};id<MTLComputePipelineState>ps[10];
 for(int i=0;i<10;i++){id<MTLFunction>f=[library newFunctionWithName:[NSString stringWithUTF8String:names[i]]];ps[i]=[d newComputePipelineStateWithFunction:f error:&error];check(ps[i]!=nil,error.description);[f release];}
 check(ps[7].threadExecutionWidth==32&&ps[8].threadExecutionWidth==32,@"Fixture requires 32-wide SIMD groups");
 enum{COUNT=1025,MAX_RECS=129,CAP=COUNT*MAX_RECS};
 id<MTLBuffer>sections=[d newBufferWithLength:COUNT*sizeof(Section) options:MTLResourceStorageModeShared],records=[d newBufferWithLength:CAP*sizeof(Record) options:MTLResourceStorageModeShared],candidates=[d newBufferWithLength:(COUNT+2)*4 options:MTLResourceStorageModeShared],bins=[d newBufferWithLength:4*4097*4 options:MTLResourceStorageModeShared],selected=[d newBufferWithLength:(COUNT+2)*4 options:MTLResourceStorageModePrivate];
 id<MTLBuffer>args[2],stats[2],lists[2];for(int i=0;i<2;i++){args[i]=[d newBufferWithLength:48 options:MTLResourceStorageModeShared];stats[i]=[d newBufferWithLength:32 options:MTLResourceStorageModeShared];lists[i]=[d newBufferWithLength:(NSUInteger)CAP*64*4 options:MTLResourceStorageModeShared];}
 const char*labels[]={"dense","sparse","empty","depth-reject","uneven","few-records","no-receiver","fallback-grid","short-lists"};
 printf("device=%s\n",d.name.UTF8String);
 for(int mode=0;mode<9;mode++)for(int exact=0;exact<2;exact++){
  Section*s=sections.contents;Record*r=records.contents;uint32_t*ids=candidates.contents,*grid=bins.contents;memset(s,0,sections.length);memset(r,0,records.length);memset(grid,0,bins.length);
  uint32_t used=0,n=mode==5?7:COUNT;
  for(uint32_t slot=0;slot<COUNT;slot++){
   int counts[]={1,7,31,32,33,65,129};uint32_t size=mode==4?counts[slot%7]:mode==5?7:mode==8?3:64;
   s[slot]=(Section){.x=((int)(slot%32)-16)*16,.y=((int)((slot/32)%4)-2)*16,.z=((int)(slot/128)-4)*16,.start=used,.count=size};ids[slot]=mode==5?528+slot:COUNT-1-slot;
   for(uint32_t i=0;i<size;i++){
    uint32_t at=used+i;int x=(i*7+slot)%13,y=(i*3)%13,z=(i*11+slot)%13;
    r[at]=(Record){.start=at*64,.slot=slot,.meta=((i*13+slot)%64)|((i%11==0?2:i%3==0?1:0)<<9),.lo=pack(x,y,z),.hi=pack(x+3,y+3,z+3)};
   }
   used+=size;
  }
  // A dead slot, an out-of-range candidate, a clipped record tail and unaligned dispatch counts.
  s[17].count=0;ids[n]=COUNT+3;ids[n+1]=17;
  uint32_t candidateCount=n+2;
  for(uint32_t i=0;i<4096;i++){
   BOOL occupied=mode==0||mode==3||mode==4||mode==5||mode==6||mode==7||mode==8||(mode==1&&(i%23==0));
   grid[1+i]=occupied?bits(mode==3?.02f:.85f):0;
  }
  if(mode==7)grid[0]=1;
  float range=mode==5?24:mode==1?96:256;
  Sun f={.clip={.columns={{1.f/range,0,0,0},{0,.6f/range,-.003f,0},{0,.8f/range,.001f,0},{0,0,.5f,1}}},.config={range,1.f/4096,.00015f,1},.sections=COUNT,.capacity=used-1,.resolution=4096,.on=1};
  Cull cull={.count=mode==6?2:0,.nearRadius=8,.planes={{1,0,0,100},{0,1,0,48}}};
  Surface surface={.enabled=mode!=6,.candidates=mode==6?UINT32_MAX:candidateCount,.grid={0,0,1,0}};
  double times[2][16];
  for(int repeat=0;repeat<20;repeat++)for(int turn=0;turn<2;turn++){
   int variant=(repeat+turn)&1;memset(stats[variant].contents,0,32);memset(args[variant].contents,0xa5,48);
   id<MTLCommandBuffer>cmd=[queue commandBuffer];id<MTLComputeCommandEncoder>c=[cmd computeCommandEncoder];
   [c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:sections offset:0 atIndex:1];[c setBuffer:records offset:0 atIndex:2];[c setBuffer:args[variant] offset:0 atIndex:3];[c setBuffer:lists[variant] offset:0 atIndex:4];[c setBytes:&cull length:sizeof cull atIndex:5];[c setBuffer:bins offset:0 atIndex:6];[c setBytes:&surface length:sizeof surface atIndex:7];[c setBuffer:candidates offset:0 atIndex:8];[c setBuffer:stats[variant] offset:0 atIndex:9];[c setBuffer:selected offset:0 atIndex:10];
   [c setComputePipelineState:ps[variant?4+exact:exact]];[c dispatchThreads:MTLSizeMake(variant?12:4,1,1) threadsPerThreadgroup:MTLSizeMake(variant?12:4,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
   [c setComputePipelineState:ps[variant?(exact?9:6):2+exact]];[c dispatchThreadgroups:MTLSizeMake((candidateCount+63)/64,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
   if(variant){[c memoryBarrierWithScope:MTLBarrierScopeBuffers];[c setComputePipelineState:ps[7+exact]];[c dispatchThreadgroupsWithIndirectBuffer:args[variant] indirectBufferOffset:32 threadsPerThreadgroup:MTLSizeMake(64,1,1)];}
   [c endEncoding];[cmd commit];[cmd waitUntilCompleted];check(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.description);if(repeat>=4)times[variant][repeat-4]=(cmd.GPUEndTime-cmd.GPUStartTime)*1000;
  }
  uint32_t*a=args[0].contents,*b=args[1].contents,count=exact?a[0]/6:a[1];check(!memcmp(a,b,16),@"Indirect draw counts differ");check(count<=CAP*64,@"List overflow");check(!memcmp(stats[0].contents,stats[1].contents,32),@"Selection/rejection statistics differ");
  qsort(lists[0].contents,count,4,compare);qsort(lists[1].contents,count,4,compare);check(!memcmp(lists[0].contents,lists[1].contents,count*4),@"Selected record/quad multiset differs");
  uint32_t*st=stats[0].contents;printf("mode=%s exact=%d oldMs=%.4f newMs=%.4f selected=%u sectionsTested=%u sectionsRejected=%u recordsTested=%u recordsRejected=%u\n",labels[mode],exact,median(times[0],16),median(times[1],16),count,st[0],st[1],st[2],st[3]);fflush(stdout);
 }
 puts("PASS: exact caster multisets, indirect counts and rejection statistics match in all fixtures");
}return 0;}
