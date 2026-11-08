#pragma once
#include <simd/simd.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

typedef struct {
    simd_float4x4 clip;
    double camera[3];
    uint64_t revision,world;
    uint32_t enabled,flags; // bit 0 skips receiver grid, bit 1 keeps deferred lighting independent of culling
} OwnSunSurfaceView;
_Static_assert(sizeof(OwnSunSurfaceView)==112,"Surface view ABI");
enum { OWN_SUN_NO_RECEIVER_GRID=1u, OWN_SUN_DEFERRED_LIGHTING=2u };
typedef struct {
    simd_float4x4 inverse;
    simd_float4 nearLo,nearHi;
    simd_uint4 grid; // screen pixels, pixels per source texel, reserved
} OwnSunSurfaceBuild;
_Static_assert(sizeof(OwnSunSurfaceBuild)==112,"Surface build ABI");
typedef struct { uint32_t enabled;float guard,nearRadius;uint32_t candidateCount;simd_uint4 grid; } OwnSunSurfaceCull;
_Static_assert(sizeof(OwnSunSurfaceCull)==32,"Surface cull ABI");

static inline uint32_t ownSunFloatKey(float v) {
    uint32_t u;memcpy(&u,&v,4);return u&0x80000000u?~u:u^0x80000000u;
}
static inline float ownSunFloatValue(uint32_t v) {
    uint32_t u=v&0x80000000u?v^0x80000000u:~v;float f;memcpy(&f,&u,4);return f;
}
static inline int ownSunSurfaceSameView(const OwnSunSurfaceView *a,const OwnSunSurfaceView *b) {
    return a->enabled==1 && b->enabled==1 && a->flags==b->flags && a->revision==b->revision && a->world==b->world &&
        !memcmp(&a->clip,&b->clip,sizeof a->clip) && !memcmp(a->camera,b->camera,sizeof a->camera);
}
// Reverse Z only: the opaque Hi-Z contains each tile's farthest depth.
static inline int ownSunSurfaceBuild(const OwnSunSurfaceView *view,uint32_t width,uint32_t height,uint32_t tile,OwnSunSurfaceBuild *out) {
    if(view->enabled!=1 || !width || !height || !tile)return 0;
    for(int i=0;i<3;i++)if(!isfinite(view->camera[i]))return 0;
    for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(!isfinite(view->clip.columns[c][r]))return 0;
    float determinant=simd_determinant(view->clip);if(!isfinite(determinant)||fabsf(determinant)<1e-15f)return 0;
    memset(out,0,sizeof *out);out->inverse=simd_inverse(view->clip);
    for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(!isfinite(out->inverse.columns[c][r]))return 0;
    simd_float4 near=simd_mul(out->inverse,(simd_float4){0,0,1,1}),middle=simd_mul(out->inverse,(simd_float4){0,0,.5f,1});
    if(near.w<=1e-8f||middle.w<=1e-8f)return 0;
    if(simd_length_squared(near.xyz/near.w)>=simd_length_squared(middle.xyz/middle.w))return 0;
    simd_float3 lo={INFINITY,INFINITY,INFINITY},hi=-lo;
    for(int i=0;i<4;i++) {
        simd_float4 q=simd_mul(out->inverse,(simd_float4){i&1?1:-1,i&2?1:-1,1,1});
        if(!isfinite(q.w)||q.w<=1e-8f)return 0;
        simd_float3 p=q.xyz/q.w;for(int j=0;j<3;j++)if(!isfinite(p[j]))return 0;
        lo=simd_min(lo,p);hi=simd_max(hi,p);
    }
    out->nearLo=(simd_float4){lo.x,lo.y,lo.z,0};out->nearHi=(simd_float4){hi.x,hi.y,hi.z,0};
    out->grid=(simd_uint4){width,height,tile,0};return 1;
}
