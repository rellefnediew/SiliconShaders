#pragma once
// Actual siliconshaders render path: a raised quad must cast a directional shadow onto a separate ground quad.
#import <Foundation/Foundation.h>
#import <simd/simd.h>
#import "mcmetal.h"
extern Ctx *mc_create(char *,int,char *,int);
extern Enc *mc_enc_new(Ctx *);
extern id<MTLCommandBuffer> mc_enc_commit(Enc *);
extern void *mco_new(Ctx *,const char *,int,int,int,char *,int);
extern int mco_sun_frame(void *,Enc *,const void *,int,id<MTLBuffer>,id<MTLBuffer>,id<MTLBuffer>,id<MTLTexture>,id<MTLSamplerState>,char *,int);
extern int mc_render_begin(Enc *,int,id<MTLTexture> const *,const float *,id<MTLTexture>,int,float,int,int);
extern int mco_draw(void *,Enc *,int,const void *,int,id<MTLBuffer>,id<MTLBuffer>,id<MTLBuffer>,id<MTLBuffer>,id<MTLBuffer>,int64_t,const int64_t *,id<MTLTexture>,id<MTLSamplerState>,id<MTLTexture>,id<MTLSamplerState>,id<MTLBuffer>,char *,int);

typedef struct { float p[3];uint8_t color[4];float uv[2];int16_t light[2]; } Vertex;
typedef struct { int x,y,z,upload;uint32_t start,count,p0,p1; } Section;
typedef struct { uint32_t start,slot,meta;int plane;uint32_t lo,hi,p0,p1; } Record;
typedef struct { simd_float4x4 clip;simd_int4 cam;simd_float4 offset,sun,config;uint32_t sections,capacity,resolution,on; } Sun;
static void require(BOOL ok,NSString *message) { if(!ok) { fprintf(stderr,"FAIL: %s\n",message.UTF8String);exit(1); } }
static id<MTLBuffer> buffer(Ctx *ctx,const void *bytes,NSUInteger length) { return [ctx->device newBufferWithBytes:bytes length:length options:MTLResourceStorageModeShared]; }
static id<MTLTexture> white(Ctx *ctx) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:4 mipmapped:NO];
    td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> t=[ctx->device newTextureWithDescriptor:td];uint8_t pixels[64];memset(pixels,255,sizeof pixels);
    [t replaceRegion:MTLRegionMake2D(0,0,4,4) mipmapLevel:0 withBytes:pixels bytesPerRow:16];return t;
}


// Optional integration coverage for real stage counters, with no Minecraft window.
extern int mc_profile_begin(Enc *,int);
extern id<MTLCounterSampleBuffer> mc_profile_end(Enc *,int *);
extern void mc_profile_labels(Enc *,char *,int,int);
extern void mc_profile_read(Ctx *,id<MTLCounterSampleBuffer>,int,double *);
static BOOL testProfileOn(void) { return getenv("SILICONSHADERS_TEST_PROFILE")!=NULL; }
static void testProfileBegin(Enc *enc) {
    if(testProfileOn()) require(mc_profile_begin(enc,512),@"Stage counters unavailable");
}
static id<MTLCommandBuffer> testProfileCommit(Enc *enc,Ctx *ctx) {
    int count=0;char labels[512][160]={0};id<MTLCounterSampleBuffer> samples=nil;
    if(testProfileOn()){mc_profile_labels(enc,(char *)labels,160,512);samples=mc_profile_end(enc,&count);}
    id<MTLCommandBuffer> command=mc_enc_commit(enc);
    if(samples) {
        [command waitUntilCompleted];require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);
        double times[2048];mc_profile_read(ctx,samples,count,times);
        BOOL cull=NO,map=NO,temporal=NO,copies=NO;int valid=0;
        for(int g=0;g<count/4;g++) {
            BOOL timed=NO;
            for(int k=0;k<4;k+=2)if(times[g*4+k]>=0 && times[g*4+k+1]>=0) {
                require(times[g*4+k+1]>=times[g*4+k],@"Backwards stage timestamps");valid++;timed=YES;
            }
            if(strstr(labels[g],"sun caster cull")){cull=timed;}
            if(strstr(labels[g],"sun shadow map")){map=timed;}
            if(strstr(labels[g],"shadow temporal:")){temporal=timed;}
            if(strstr(labels[g],"sun temporal color/depth")){copies=timed;}
        }
        require(valid>0 && cull && map,@"Real shadow/cull timestamps missing");
        require(temporal==copies,@"Temporal copies/resolve profiling incomplete");
        printf("profile: %d groups, cull/map timed, temporal=%d copies=%d\n",count/4,temporal,copies);
    }
    return command;
}
