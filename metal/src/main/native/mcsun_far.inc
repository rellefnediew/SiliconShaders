// Far shadow level from a terrain cache. The terrain depth is rendered without view-dependent
// selection and reused while it is indistinguishable from a fresh render; entity casters are drawn
// on a copy every frame. Reuse shifts the recorded light clip by the camera's movement, so world
// geometry keeps its exact texels. Any change that could alter the map refreshes it:
// - a relevant terrain upload or removal (OwnTerrain's sun geometry revision),
// - shadow size/range/level settings, section count or atlas,
// - a sun rotation that could move a 32-block caster's shadow by half a far-map texel,
// - camera movement beyond 7% of the far range (shadows past 90% are faded out),
// - an age of 2 s, bounding anything not listed (e.g. animated cutout textures).
// Foliage sway reaches ~0.13 blocks (OwnWind: leaf offset 0.07 x gain cap 1.75). When a far-map
// texel is at least SUN_FAR_STILL_TEXEL (twice that; game settings give >= ~0.4 blocks), the far
// level draws foliage without wind, so the moving wind clock does not refresh the cache every
// frame. Finer far maps keep the wind and refresh whenever its clock or strength changes.
enum { SUN_FAR_OFF, SUN_FAR_REFRESH, SUN_FAR_REUSE };
#define SUN_FAR_CASTER_HEIGHT 32.0f
#define SUN_FAR_MOVE 0.07f
#define SUN_FAR_MAX_AGE 2.0
#define SUN_FAR_STILL_TEXEL 0.3f

void mco_sun_far_cache(Own *w,int enabled,uint64_t revision) { w->sunFarEnabled=enabled!=0;w->sunFarRevisionNow=revision; }
int mco_sun_debug_far_mode(Own *w) { return w->sunFarMode; }
int mco_sun_debug_far_direct(Own *w) { return w->sunFarDirect; }
void mco_sun_debug_far_stats(Own *w,uint64_t *stats) { stats[0]=w->sunFarRefreshes;stats[1]=w->sunFarReuses; }
// Fixtures with fine far maps can lower the still-foliage texel limit (0 restores the default).
void mco_sun_debug_far_still_texel(Own *w,float texel) { w->sunFarStillTexel=texel; }

static void sunFarDrop(Own *w) {
    [w->sunFarCache release];w->sunFarCache=nil;w->sunFarValid=0;w->sunFarMode=SUN_FAR_OFF;w->sunFarDirect=0;w->sunFarActorRectCount=-1;w->sunFarActorTarget=0;
    [w->sunFarActorScratch release];w->sunFarActorScratch=nil;
}
// Decides this frame's far level before any cascade is encoded. On reuse, rewrites the far
// sample's clip (first 64 bytes) in the caller's packet copy, so receivers, glass, entity casters
// and the shading all address the cached map consistently.
static int sunFarPrepare(Own *w,uint8_t *packet,int length,int cascaded,id<MTLTexture> atlas,char *err,int cap) {
    w->sunFarMode=SUN_FAR_OFF;w->sunFarDirect=0;
    if(!w->sunFarEnabled || (length!=688&&length!=400) || cascaded<2) { if(w->sunFarCache)sunFarDrop(w);return 0; }
    uint8_t *far=packet+256+(cascaded-2)*144;
    // Without direct sun no map is drawn, so a cache from before must not be reused afterwards.
    if(*(const float *)(far+108)<=0) { w->sunFarValid=0;return 0; }
    uint32_t size=*(const uint32_t *)(far+136);float range=*(const float *)(far+112);
    // OwnWind's cascade fields: +76 strength, +92 clock.
    if(2*range/(float)size>=(w->sunFarStillTexel>0?w->sunFarStillTexel:SUN_FAR_STILL_TEXEL)) { *(float *)(far+76)=0;*(float *)(far+92)=0; }
    const double *camera=(const double *)(packet+208);const float *direction=(const float *)(far+96);
    if(!w->sunFarCache || w->sunFarCache.width!=size) {
        [w->sunFarCache release];w->sunFarCache=sunTexture(w,(int)size,err,cap);w->sunFarValid=0;w->sunFarActorRectCount=-1;w->sunFarActorTarget=0;
        if(!w->sunFarCache)return -1;
    }
    double now=CACurrentMediaTime();
    BOOL reuse=w->sunFarValid && w->sunFarLevels==cascaded && w->sunFarRevision==w->sunFarRevisionNow &&
        w->sunFarAtlas==(uintptr_t)atlas && !memcmp(w->sunFarKey,far+112,32) && now-w->sunFarTime<SUN_FAR_MAX_AGE;
    // Animated foliage in a fine far map: a changed wind clock or strength refreshes it.
    float windTime=*(const float *)(far+92),windStrength=*(const float *)(far+76);
    if(windStrength>0 || w->sunFarWindStrength>0)
        reuse=reuse && windTime==w->sunFarWindTime && windStrength==w->sunFarWindStrength;
    if(reuse) {
        // A caster h above its receiver moves its shadow by h*dθ/sin²(elevation).
        float dx=direction[0]-w->sunFarDirection[0],dy=direction[1]-w->sunFarDirection[1],dz=direction[2]-w->sunFarDirection[2];
        float turned=2*asinf(fminf(1,sqrtf(dx*dx+dy*dy+dz*dz)*.5f)),elevation=fmaxf(direction[1],0);
        float texel=2*range/(float)size,allowed=.5f*texel*fmaxf(elevation*elevation,1e-4f)/SUN_FAR_CASTER_HEIGHT;
        double mx=camera[0]-w->sunFarCamera[0],my=camera[1]-w->sunFarCamera[1],mz=camera[2]-w->sunFarCamera[2];
        reuse=isfinite(turned) && turned<=allowed && mx*mx+my*my+mz*mz<=(double)(SUN_FAR_MOVE*range)*(SUN_FAR_MOVE*range);
        if(reuse) {
            // Positions are camera-relative: p_cached = p_now + (camera_now - camera_cached).
            simd_float4x4 shift=matrix_identity_float4x4;shift.columns[3]=(simd_float4){(float)mx,(float)my,(float)mz,1};
            simd_float4x4 clip=simd_mul(w->sunFarClip,shift);memcpy(far,&clip,64);
        }
    }
    if(!reuse) {
        w->sunFarWindTime=windTime;w->sunFarWindStrength=windStrength;
        memcpy(&w->sunFarClip,far,64);memcpy(w->sunFarCamera,camera,24);memcpy(w->sunFarDirection,direction,12);
        memcpy(w->sunFarKey,far+112,32);w->sunFarAtlas=(uintptr_t)atlas;w->sunFarRevision=w->sunFarRevisionNow;
        w->sunFarLevels=cascaded;w->sunFarTime=now;w->sunFarValid=1;
    }
    w->sunFarMode=reuse?SUN_FAR_REUSE:SUN_FAR_REFRESH;
    if(reuse)w->sunFarReuses++;else w->sunFarRefreshes++;
    // Without entity casters the cache itself is the far map: no copy, no extra pass.
    BOOL actors=NO;for(int i=0;i<w->sunCasterCount&&!actors;i++)actors=w->sunCasters[i].config.x<0.5f&&w->sunCasters[i].draw.x>0;
    w->sunFarDirect=!actors;
    return 0;
}
// Encodes one cascade; the far level goes through the cache when it is active.
static int sunEncodeCascade(Own *w,Enc *enc,const void *sample,id<MTLTexture> map,id<MTLBuffer> arena,id<MTLBuffer> sections,
    id<MTLBuffer> recs,id<MTLTexture> atlas,id<MTLSamplerState> sampler,int level,char *err,int cap) {
    // No direct sun: nothing samples the maps (sunCurrentMask returns lit), so none is drawn.
    if(*(const float *)((const uint8_t *)sample+108)<=0)return 0;
    if(!w->sunFarMode || level!=w->sunCascaded-1)return sunEncode(w,enc,sample,map,arena,sections,recs,atlas,sampler,level,err,cap);
    if(w->sunFarMode==SUN_FAR_REFRESH &&
        sunEncodeMode(w,enc,sample,w->sunFarCache,arena,sections,recs,atlas,sampler,level,SUN_ENCODE_TERRAIN,nil,err,cap)<0)return -1;
    if(w->sunFarDirect)return 0;
    return sunEncodeMode(w,enc,sample,map,arena,sections,recs,atlas,sampler,level,SUN_ENCODE_ACTORS,w->sunFarCache,err,cap);
}
