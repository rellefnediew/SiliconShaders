// Compatible view packet. Current-frame receiver culling uses no depth history.
int mco_sun_surface(Own *w,const OwnSunSurfaceView *view) {
    if(!view)return -1;
    w->sunSurface.current=*view;
    // The draw's full projection includes view bobbing/hurt transforms. The perspective center is inverse(clip) * (0,0,1,0).
    simd_float4 eye=simd_inverse(view->clip).columns[2];w->surfaceEye=(simd_float3){0,0,0};
    if(isfinite(eye.x)&&isfinite(eye.y)&&isfinite(eye.z)&&isfinite(eye.w)&&fabsf(eye.w)>1e-8f)w->surfaceEye=eye.xyz/eye.w;
    if(view->enabled!=1)w->sunSurface.current.enabled=0;
    return 0;
}
