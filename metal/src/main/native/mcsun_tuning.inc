// Native lighting values (SunTuning.java), environment, moonlight and the hand light, packed for the
// shadow resolve passes (sun.metal SunLighting).
#define SUN_TUNING_COUNT 76
static const float sunTuningDefaults[SUN_TUNING_COUNT]={1.0f,0.85f,0.58f,2.4f,1.0f,0.55f,0.28f,2.2f,0.5f,0.66f,1.0f,0.75f,0.64f,0.52f,0.62f,0.55f,0.46f,0.52f,0.85f,0.18f,1.0f,0.6f,0.38f,1.0f,0.02f,0.45f,1.0f,1.0f,0.85f,0.6f,0.45f,0.0f,1.0f,1.0f,1.0f,1.0f,0.0f,0.0f,0.0f,0.0f,0.22f,0.44f,0.96f,1.0f,0.64f,0.8f,1.0f,1.0f,0.16f,0.22f,0.5f,0.06f,0.3f,0.38f,0.62f,0.1f,1.0f,0.46f,0.22f,1.0f,1.0f,0.5f,1.0f,1.0f,0.003f,0.02f,1.0f,1.0f,0.75f,0.7f,1.0f,0.18f,1.0f,0.0f,0.0f,0.0f};
// Mirrors sun.metal's SunLighting (864 bytes). moon: 1 while the moon is the directional light.
// hand[0]: light origin (camera-relative), main-hand level; hand[1]: main-hand color, off-hand level;
// hand[2]: off-hand color (red < 0: block light color).
typedef struct {
    uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];
    simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];
} OwnSunLighting;
_Static_assert(sizeof(OwnSunLighting)==864,"SunLighting ABI");

#include "mcaurora_noise.h"
static id<MTLTexture> sunAuroraNoise(Own *w) {
    if(!w->sunAuroraNoise) {
        uint8_t bytes[128*128*4];mc_aurora_noise_fill(bytes);
        MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:YES];
        d.usage=MTLTextureUsageShaderRead;d.storageMode=MTLStorageModeShared;
        w->sunAuroraNoise=[w->ctx->device newTextureWithDescriptor:d];
        for(int level=0,side=128;side>0;level++,side/=2) {
            [w->sunAuroraNoise replaceRegion:MTLRegionMake2D(0,0,side,side) mipmapLevel:level withBytes:bytes bytesPerRow:side*4];
            if(side>1)mc_aurora_noise_downsample(bytes,side);
        }
    }
    return w->sunAuroraNoise;
}
static id<MTLTexture> sunSkyNoise(Own *w) {
    if(!w->sunSkyNoise) {
        uint8_t *bytes=malloc(MC_SKY_NOISE_SIZE*MC_SKY_NOISE_SIZE*4);mc_sky_noise_fill(bytes);
        MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:MC_SKY_NOISE_SIZE height:MC_SKY_NOISE_SIZE mipmapped:YES];
        d.usage=MTLTextureUsageShaderRead;d.storageMode=MTLStorageModeShared;
        w->sunSkyNoise=[w->ctx->device newTextureWithDescriptor:d];
        for(int level=0,side=MC_SKY_NOISE_SIZE;side>0;level++,side/=2) {
            [w->sunSkyNoise replaceRegion:MTLRegionMake2D(0,0,side,side) mipmapLevel:level withBytes:bytes bytesPerRow:side*4];
            if(side>1)mc_sky_noise_downsample(bytes,side);
        }
        free(bytes);
    }
    return w->sunSkyNoise;
}
// Three explicit float4s extend the lighting packet; no more reuse of moon/hand slots for new controls.
void mco_sun_aurora(Own *w,const float *values,int count) {
    if(!w||!values||count!=28)return;
    for(int i=0;i<28;i++)if(!isfinite(values[i]))return;
    // A dimension switch must not inherit temporal visibility or the previous world's cached far casters.
    if(w->sunSky[3]!=values[15]) {
        w->sunTemporalValid=0;sunClearCache(w);sunFarDrop(w);w->sunMap=w->sunSecondMap=nil;
    }
    memcpy(w->sunAurora,values,sizeof w->sunAurora);memcpy(w->sunSky,values+12,sizeof w->sunSky);
}
// Color grading (SunLook.grade): white balance, lift and vibrance, gamma and curve, gain and white scale. Slot 3 is the
// automatic exposure factor, which native code owns (mco_sun_exposure).
void mco_sun_grade(Own *w,const float *values,int count) {
    if(!w||!values||count!=16)return;
    for(int i=0;i<16;i++)if(!isfinite(values[i]))return;
    memcpy(w->sunGrade,values,sizeof w->sunGrade);w->sunGradeSet=1;
}
void mco_sun_celestial(Own *w,const float *values,int count) {
    if(!w||!values||count!=56)return;
    for(int i=0;i<56;i++)if(!isfinite(values[i]))return;
    memcpy(w->sunCelestial,values,sizeof w->sunCelestial);sunFxRefresh(w);
}
void mco_sun_hdr(Own *w,int enabled){if(!w)return;int next=enabled!=0;if(next!=w->bloomHdrActive)w->sunTemporalValid=0;w->bloomHdrActive=next;}
void mco_sun_tuning(Own *w,const float *values,int count) {
    if(!values||count!=SUN_TUNING_COUNT)return;
    for(int i=0;i<count;i++)if(!isfinite(values[i]))return;
    memcpy(w->sunTuning,values,sizeof w->sunTuning);w->sunTuningSet=1;sunFxRefresh(w);
}
void mco_sun_environment(Own *w,float moon,float rain,int fluid,int sky) {
    w->sunEnv[0]=isfinite(moon)?fminf(fmaxf(moon,0),2):1;w->sunEnv[1]=isfinite(rain)?fminf(fmaxf(rain,0),1):0;
    w->sunEnv[2]=fluid?1:0;w->sunEnv[3]=sky?1:0;w->sunEnvSet=1;
}
// Java's moon shadows: the frame's directional light is the moon (direction and strength in the packet).
void mco_sun_moon_light(Own *w,int moon) { w->sunMoonLight=moon!=0; }
// SunHandLight's 12 floats (OwnTerrain.updateHandLight); levels 0 switch it off.
void mco_sun_hand(Own *w,const float *hand) {
    memset(w->sunHand,0,sizeof w->sunHand);
    if(!hand)return;
    for(int i=0;i<12;i++)if(!isfinite(hand[i]))return;
    memcpy(w->sunHand,hand,sizeof w->sunHand);
    w->sunHand[3]=fminf(fmaxf(w->sunHand[3],0),15);w->sunHand[7]=fminf(fmaxf(w->sunHand[7],0),15);
}

static const float *sunTuningValues(Own *w) { return w->sunTuningSet?w->sunTuning:sunTuningDefaults; }
// The lighting packet for the resolve passes: tuning, environment, moonlight flag and hand light.
static void sunLightingFill(Own *w,OwnSunLighting *l) {
    memcpy(l->celestial,w->sunCelestial,sizeof w->sunCelestial);
    memcpy(l->aurora,w->sunAurora,sizeof w->sunAurora);memcpy(l->sky,w->sunSky,sizeof w->sunSky);
    static const float neutralGrade[16]={1,1,1,1, 0,0,0,0, 1,1,1,0, 1,1,1,1};
    memcpy(l->grade,w->sunGradeSet?w->sunGrade:neutralGrade,sizeof l->grade);l->grade[0].w=exp2f(w->sunAutoLog);
    memcpy(l->materials,w->surfaceMaterials,sizeof w->surfaceMaterials);l->materials[3].w=w->bloomHdrActive;
    const float *t=sunTuningValues(w);memcpy(l->p,t,sizeof(float)*SUN_TUNING_COUNT);l->moon=w->sunMoonLight?1:0;
    // Four formerly unused hand slots carry surface controls, preserving the original lighting prefix and HDR flag.
    memcpy(&l->p[9],w->surfaceDetail,sizeof w->surfaceDetail);
    l->env=w->sunEnvSet?(simd_float4){w->sunEnv[0],w->sunEnv[1],w->sunEnv[2],w->sunEnv[3]}:(simd_float4){1,0,0,0};
    for(int i=0;i<3;i++)l->hand[i]=(simd_float4){w->sunHand[i*4],w->sunHand[i*4+1],w->sunHand[i*4+2],w->sunHand[i*4+3]};
    // Reserved padding + unused fourth offhand-color component; no shared-lighting ABI growth.
    l->pad[0]=w->surfaceEye.x;l->pad[1]=w->surfaceEye.y;l->hand[2].w=w->surfaceEye.z;
}
