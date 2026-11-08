// Pure CPU tests: no Metal device, command buffer, draws or game.
#include "mcsun_surface.h"
#include <assert.h>
#include <stdio.h>

static simd_float3 unproject(simd_float4x4 inverse,float x,float y,float z) {
    simd_float4 p=simd_mul(inverse,(simd_float4){x,y,z,1});return p.xyz/p.w;
}
static float hash01(uint32_t *seed) { *seed=*seed*1664525u+1013904223u;return (*seed>>8)*(1.0f/16777216); }
int main(void) {
    assert(sizeof(OwnSunSurfaceView)==112 && sizeof(OwnSunSurfaceBuild)==112 && sizeof(OwnSunSurfaceCull)==32);
    const float f=1.3f,n=.05f;
    simd_float4x4 projection={.columns={{f/1.6f,0,0,0},{0,f,0,0},{0,0,0,-1},{0,0,n,0}}};
    simd_float4x4 down={.columns={{1,0,0,0},{0,0,1,0},{0,-1,0,0},{0,0,0,1}}};
    OwnSunSurfaceView view={.clip=simd_mul(projection,down),.revision=7,.world=2,.enabled=1};
    OwnSunSurfaceBuild build;assert(ownSunSurfaceBuild(&view,1920,1080,4,&build));
    OwnSunSurfaceView other=view;assert(ownSunSurfaceSameView(&view,&other));
    other.camera[0]=.0001;assert(!ownSunSurfaceSameView(&view,&other));other=view;other.world++;
    assert(!ownSunSurfaceSameView(&view,&other));other=view;other.revision++;
    assert(!ownSunSurfaceSameView(&view,&other));other=view;other.clip.columns[0].x+=.0001f;
    assert(!ownSunSurfaceSameView(&view,&other));other=view;other.enabled=0;assert(!ownSunSurfaceSameView(&view,&other));
    assert(!ownSunSurfaceBuild(&view,0,1080,4,&build));assert(!ownSunSurfaceBuild(&view,1920,1080,0,&build));
    other=view;other.clip=(simd_float4x4){0};assert(!ownSunSurfaceBuild(&other,1920,1080,4,&build));
    other=view;other.camera[0]=NAN;assert(!ownSunSurfaceBuild(&other,1920,1080,4,&build));
    other=view;other.clip.columns[0].x=NAN;assert(!ownSunSurfaceBuild(&other,1920,1080,4,&build));
    simd_float4x4 regular=projection;regular.columns[2].z=-1;regular.columns[3].z=-n;
    other=view;other.clip=simd_mul(regular,down);assert(!ownSunSurfaceBuild(&other,1920,1080,4,&build));
    assert(ownSunSurfaceBuild(&view,1920,1080,4,&build));
    const float values[]={-1e6f,-1,-.01f,-0.0f,0,.01f,1,1e6f};
    for(unsigned i=0;i<sizeof values/sizeof values[0];i++) {
        float decoded=ownSunFloatValue(ownSunFloatKey(values[i]));assert(!memcmp(&decoded,values+i,4));
        if(i)assert(ownSunFloatKey(values[i-1])<ownSunFloatKey(values[i]));
    }
    simd_float3 lo=build.nearLo.xyz,hi=build.nearHi.xyz;
    // Downward view of a floor two blocks below: it must not extend underground.
    for(int c=0;c<4;c++) {simd_float3 p=unproject(build.inverse,c&1?1:-1,c&2?1:-1,n/2);lo=simd_min(lo,p);hi=simd_max(hi,p);}
    assert(lo.y> -2.001f && hi.y<0 && hi.x-lo.x<6 && hi.z-lo.z<6);
    uint32_t seed=41561;
    // Any receiver in a tile, at any depth between its near/far planes, lies in
    // the union AABB. Sweeping a caster toward the sun preserves light XY.
    for(int i=0;i<10000;i++) {
        float x=hash01(&seed)*2-1,y=hash01(&seed)*2-1,z=n/2+hash01(&seed)*(1-n/2);
        simd_float3 receiver=unproject(build.inverse,x,y,z);
        assert(simd_all(receiver>=lo-1e-4f)&&simd_all(receiver<=hi+1e-4f));
        simd_float3 sun=simd_normalize((simd_float3){hash01(&seed)*2-1,.2f+hash01(&seed),hash01(&seed)*2-1});
        simd_float3 axis=simd_normalize(simd_cross(sun,(simd_float3){0,0,1}));
        simd_float3 caster=receiver+sun*(hash01(&seed)*1024);
        float projected=simd_dot(axis,caster),center=simd_dot(axis,(lo+hi)*.5f),extent=simd_dot(simd_abs(axis),(hi-lo)*.5f);
        assert(fabsf(projected-center)<=extent+1e-3f);
    }
    // A sky tile of an infinite reverse projection cannot safely yield bounds.
    simd_float4 sky=simd_mul(build.inverse,(simd_float4){0,0,0,1});assert(sky.w==0);
    puts("PASS: surface ABI, view/revision invalidation, reverse-Z bounds, signed float order, 10000 receiver/light rays and sky fallback (CPU only)");
    return 0;
}
