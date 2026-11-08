// Opaque/cutout split of the shadow caster list: for every selector (serial, grouped) and list
// form (record, exact quad), the front (opaque) and back (cutout) regions together must hold
// exactly the combined list's entries, each on its correct side. No live game is started.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef struct {int x,y,z,upload;uint32_t start,count,pad[2];} Section;
typedef struct {uint32_t start,slot,meta;int plane;uint32_t lo,hi,pad[2];} Record;
typedef struct {simd_float4x4 clip;simd_int4 cam;simd_float4 offset,sun,config;uint32_t sections,capacity,resolution,on;} Sun;
typedef struct {uint32_t count;float nearRadius;uint32_t pad[2];simd_float4 planes[16];} Cull;
typedef struct {uint32_t enabled;float guard,nearRadius;uint32_t candidates;simd_uint4 grid;} Surface;
static void check(BOOL ok,NSString *why){if(!ok){fprintf(stderr,"FAIL: %s\n",why.UTF8String);exit(1);}}
static int compare(const void*a,const void*b){uint32_t x=*(const uint32_t*)a,y=*(const uint32_t*)b;return (x>y)-(x<y);}
static uint32_t pack(int x,int y,int z){return (x+16)|((y+16)<<8)|((z+16)<<16);}
static uint32_t bits(float x){uint32_t u;memcpy(&u,&x,4);return u;}
int main(int argc,const char**argv){@autoreleasepool{
 check(argc==3,@"Usage: sun_depth_split_check TERRAIN SUN");NSError*error=nil;id<MTLDevice>d=MTLCreateSystemDefaultDevice();check(d!=nil,@"No Metal device");id<MTLCommandQueue>queue=[d newCommandQueue];
 NSString*terrain=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error];NSString*sun=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&error];
 MTLCompileOptions*options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;options.mathMode=MTLMathModeFast;
 id<MTLLibrary>library=[d newLibraryWithSource:[terrain stringByAppendingString:sun] options:options error:&error];check(library!=nil,error.description);
 const char*names[]={"sun_reset","sun_reset_exact","sun_cull","sun_cull_exact","sun_reset_select","sun_reset_select_exact","sun_select_sections","sun_cull_selected","sun_cull_selected_exact","sun_select_sections_exact"};id<MTLComputePipelineState>ps[10];
 for(int i=0;i<10;i++){id<MTLFunction>f=[library newFunctionWithName:[NSString stringWithUTF8String:names[i]]];ps[i]=[d newComputePipelineStateWithFunction:f error:&error];check(ps[i]!=nil,error.description);[f release];}
 check(ps[7].threadExecutionWidth==32&&ps[8].threadExecutionWidth==32,@"Fixture requires 32-wide SIMD groups");
 // The split draw's vertex functions must exist and compile with the compact constant.
 MTLFunctionConstantValues*cv=[MTLFunctionConstantValues new];bool compact=true;[cv setConstantValue:&compact type:MTLDataTypeBool atIndex:1];
 for(int i=0;i<2;i++){id<MTLFunction>f=[library newFunctionWithName:i?@"sun_exact_cutout_vs":@"sun_cutout_vs" constantValues:cv error:&error];check(f!=nil,error.description);[f release];}
 // Indexed geometry: instance counts of 2048-quad instances for both draws, including empty and boundary counts.
 {
  id<MTLFunction>f=[library newFunctionWithName:@"sun_indexed_args"];id<MTLComputePipelineState>p=[d newComputePipelineStateWithFunction:f error:&error];check(p!=nil,error.description);[f release];
  for(int i=0;i<2;i++){id<MTLFunction>g=[library newFunctionWithName:i?@"sun_exact_indexed_cutout_vs":@"sun_exact_indexed_vs" constantValues:cv error:&error];check(g!=nil,error.description);[g release];}
  id<MTLBuffer>a=[d newBufferWithLength:128 options:MTLResourceStorageModeShared];const uint32_t quads[]={0,1,2047,2048,2049,1930278};
  for(int i=0;i<6;i++){
   uint32_t*v=a.contents;memset(v,0xa5,128);v[0]=quads[i]*6;v[1]=1;v[12]=quads[5-i]*6;v[13]=1;
   id<MTLCommandBuffer>cmd=[queue commandBuffer];id<MTLComputeCommandEncoder>c=[cmd computeCommandEncoder];[c setComputePipelineState:p];[c setBuffer:a offset:0 atIndex:3];
   [c dispatchThreads:MTLSizeMake(2,1,1) threadsPerThreadgroup:MTLSizeMake(2,1,1)];[c endEncoding];[cmd commit];[cmd waitUntilCompleted];
   for(int k=0;k<2;k++){uint32_t q=k?quads[5-i]:quads[i],*w=v+(k?24:16);check(w[0]==2048*6&&w[1]==(q+2047)/2048&&!w[2]&&!w[3]&&!w[4],@"Indexed draw arguments are wrong");check(w[1]*2048>=q&&(w[1]==0||(w[1]-1)*2048<q),@"Indexed instances do not cover the selected quads");}
  }
  puts("indexed draw arguments: 0..1930278 quads covered by whole 2048-quad instances");
 }
 enum{COUNT=1025,MAX_RECS=129,CAP=COUNT*MAX_RECS};
 id<MTLBuffer>sections=[d newBufferWithLength:COUNT*sizeof(Section) options:MTLResourceStorageModeShared],records=[d newBufferWithLength:CAP*sizeof(Record) options:MTLResourceStorageModeShared],candidates=[d newBufferWithLength:(COUNT+2)*4 options:MTLResourceStorageModeShared],bins=[d newBufferWithLength:4*4097*4 options:MTLResourceStorageModeShared],selected=[d newBufferWithLength:(COUNT+2)*4 options:MTLResourceStorageModePrivate];
 id<MTLBuffer>args=[d newBufferWithLength:64 options:MTLResourceStorageModeShared],stats=[d newBufferWithLength:32 options:MTLResourceStorageModeShared],list=[d newBufferWithLength:(NSUInteger)CAP*64*4 options:MTLResourceStorageModeShared];
 uint32_t*combined=malloc((size_t)CAP*64*4),*joined=malloc((size_t)CAP*64*4),combinedStats[8];
 const char*labels[]={"dense","sparse","uneven","no-receiver","short-lists"};const int modes[]={0,1,4,6,8};
 for(int m=0;m<5;m++)for(int exact=0;exact<2;exact++)for(int grouped=0;grouped<2;grouped++){
  int mode=modes[m];Section*s=sections.contents;Record*r=records.contents;uint32_t*ids=candidates.contents,*grid=bins.contents;memset(s,0,sections.length);memset(r,0,records.length);memset(grid,0,bins.length);
  uint32_t used=0;
  for(uint32_t slot=0;slot<COUNT;slot++){
   int counts[]={1,7,31,32,33,65,129};uint32_t size=mode==4?counts[slot%7]:mode==8?3:64;
   s[slot]=(Section){.x=((int)(slot%32)-16)*16,.y=((int)((slot/32)%4)-2)*16,.z=((int)(slot/128)-4)*16,.start=used,.count=size};ids[slot]=COUNT-1-slot;
   for(uint32_t i=0;i<size;i++){
    uint32_t at=used+i;int x=(i*7+slot)%13,y=(i*3)%13,z=(i*11+slot)%13;
    // Layers 0 (opaque), 1 (cutout) and 2 (translucent, never a caster).
    r[at]=(Record){.start=at*64,.slot=slot,.meta=((i*13+slot)%64)|(((i+slot)%11==0?2:(i+slot)%3==0?1:0)<<9),.lo=pack(x,y,z),.hi=pack(x+3,y+3,z+3)};
   }
   used+=size;
  }
  s[17].count=0;ids[COUNT]=COUNT+3;ids[COUNT+1]=17;uint32_t candidateCount=COUNT+2;
  for(uint32_t i=0;i<4096;i++){BOOL occupied=mode!=1||(i%23==0);grid[1+i]=occupied?bits(.85f):0;}
  float range=mode==1?96:256;uint32_t capacity=used;
  Sun f={.clip={.columns={{1.f/range,0,0,0},{0,.6f/range,-.003f,0},{0,.8f/range,.001f,0},{0,0,.5f,1}}},.config={range,1.f/4096,.00015f,1},.sections=COUNT,.capacity=capacity,.resolution=4096,.on=1};
  Cull cull={.count=mode==6?2:0,.nearRadius=8,.planes={{1,0,0,100},{0,1,0,48}}};
  uint32_t total=0,opaque=0,cutout=0;
  for(int split=0;split<2;split++){
   Surface surface={.enabled=mode!=6,.candidates=mode==6?UINT32_MAX:candidateCount,.grid={0,(uint32_t)split,1,0}};
   memset(stats.contents,0,32);memset(args.contents,0xa5,64);memset(list.contents,0xff,list.length);
   id<MTLCommandBuffer>cmd=[queue commandBuffer];id<MTLComputeCommandEncoder>c=[cmd computeCommandEncoder];
   [c setBytes:&f length:sizeof f atIndex:0];[c setBuffer:sections offset:0 atIndex:1];[c setBuffer:records offset:0 atIndex:2];[c setBuffer:args offset:0 atIndex:3];[c setBuffer:list offset:0 atIndex:4];[c setBytes:&cull length:sizeof cull atIndex:5];[c setBuffer:bins offset:0 atIndex:6];[c setBytes:&surface length:sizeof surface atIndex:7];[c setBuffer:candidates offset:0 atIndex:8];[c setBuffer:stats offset:0 atIndex:9];[c setBuffer:selected offset:0 atIndex:10];
   [c setComputePipelineState:ps[grouped?4+exact:exact]];[c dispatchThreads:MTLSizeMake(16,1,1) threadsPerThreadgroup:MTLSizeMake(16,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
   [c setComputePipelineState:ps[grouped?(exact?9:6):2+exact]];[c dispatchThreadgroups:MTLSizeMake((candidateCount+63)/64,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
   if(grouped){[c memoryBarrierWithScope:MTLBarrierScopeBuffers];[c setComputePipelineState:ps[7+exact]];[c dispatchThreadgroupsWithIndirectBuffer:args indirectBufferOffset:32 threadsPerThreadgroup:MTLSizeMake(64,1,1)];}
   [c endEncoding];[cmd commit];[cmd waitUntilCompleted];check(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.description);
   uint32_t*a=args.contents,*l=list.contents;uint32_t entries=exact?capacity*64:capacity;
   // Instance/vertex fields of both draws must stay a valid indirect draw.
   check(exact?(a[1]==1&&a[2]==0&&a[3]==0&&a[13]==1&&a[14]==0&&a[15]==0):(a[0]==384&&a[2]==0&&a[3]==0&&a[12]==384&&a[14]==0&&a[15]==0),@"Malformed indirect draw arguments");
   uint32_t front=exact?a[0]/6:a[1],back=exact?a[12]/6:a[13];
   if(!split){
    check(back==0,@"Combined mode wrote the cutout draw");total=front;check(total<=entries,@"List overflow");memcpy(combined,l,total*4);memcpy(combinedStats,stats.contents,32);
   } else {
    opaque=front;cutout=back;check(front+back==total,@"Split changed the caster count");
    check(!memcmp(combinedStats,stats.contents,32),@"Split changed selection statistics");
    for(uint32_t i=0;i<front;i++){uint32_t rec=exact?l[i]>>6:l[i];check(rec<capacity&&((r[rec].meta>>9)&3)==0,@"Front region holds a non-opaque caster");joined[i]=l[i];}
    for(uint32_t i=0;i<back;i++){uint32_t e=l[entries-1-i],rec=exact?e>>6:e;check(rec<capacity&&((r[rec].meta>>9)&3)==1,@"Back region holds a non-cutout caster");joined[front+i]=e;}
    // Regions must not touch: everything between them keeps the 0xff fill.
    for(uint32_t i=front;i<entries-back;i+=4099)check(l[i]==UINT32_MAX,@"Opaque and cutout regions overlap");
    qsort(combined,total,4,compare);qsort(joined,total,4,compare);check(!memcmp(combined,joined,total*4),@"Split lost or duplicated a caster");
   }
  }
  check(mode==6||cutout>0,@"Fixture produced no cutout casters");
  printf("mode=%s exact=%d grouped=%d total=%u opaque=%u cutout=%u\n",labels[m],exact,grouped,total,opaque,cutout);fflush(stdout);
 }
 puts("PASS: opaque and cutout regions partition the combined caster list for serial/grouped and record/exact selection");
}return 0;}
