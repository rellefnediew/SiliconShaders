// Depth-only SSAO: no extra geometry/G-buffer, temporal history or motion trails.
// Inverse clip is the same camera-relative reversed-Z matrix that wrote opaque depth.
constant uint SSAO_QUALITY [[function_constant(50)]];
struct SsaoFrame {float4x4 inverse;float4 screen,controls,extra;};
static float3 ssaoPosition(uint2 pixel,float z,constant SsaoFrame &f){
    float4 p=f.inverse*float4((float2(pixel)+.5)/f.screen.xy*2-1,z,1);
    return p.xyz/p.w;
}
static uint2 ssaoPixel(int2 pixel,constant SsaoFrame &f){return uint2(clamp(pixel,int2(0),int2(f.screen.xy)-1));}
// Choose the shorter derivative on each axis to avoid normals bridging silhouettes.
static float3 ssaoNormal(uint2 pixel,float3 p,depth2d<float> depth,constant SsaoFrame &f,thread float &footprint){
    uint2 l=ssaoPixel(int2(pixel)+int2(-1,0),f),r=ssaoPixel(int2(pixel)+int2(1,0),f);
    uint2 t=ssaoPixel(int2(pixel)+int2(0,-1),f),b=ssaoPixel(int2(pixel)+int2(0,1),f);
    float3 dl=p-ssaoPosition(l,max(depth.read(l),1e-8),f),dr=ssaoPosition(r,max(depth.read(r),1e-8),f)-p;
    float3 dt=p-ssaoPosition(t,max(depth.read(t),1e-8),f),db=ssaoPosition(b,max(depth.read(b),1e-8),f)-p;
    if(all(l==pixel))dl=dr;if(all(r==pixel))dr=dl;
    if(all(t==pixel))dt=db;if(all(b==pixel))db=dt;
    float3 dx=dot(dl,dl)<dot(dr,dr)?dl:dr,dy=dot(dt,dt)<dot(db,db)?dt:db;
    footprint=max(length(dx),1e-5);
    float3 n=cross(dx,dy);n*=rsqrt(max(dot(n,n),1e-16));
    return dot(n,p)>0?-n:n;
}
static float2 ssaoOct(float3 n){
    n/=max(dot(abs(n),float3(1)),1e-6);float2 s=select(float2(-1),float2(1),n.xy>=0);
    return n.z>=0?n.xy:(1-abs(n.yx))*s;
}
fragment half4 ssao_mask_fs(SunScreenVertex in [[stage_in]],constant SsaoFrame &f [[buffer(0)]],depth2d<float> depth [[texture(0)]]){
    uint2 pixel=min(uint2(in.position.xy)*2+1,uint2(f.screen.xy)-1);
    float z=depth.read(pixel);if(z<=1e-8)return half4(1,0,0,0);
    float3 p=ssaoPosition(pixel,z,f);float distance=length(p);
    if(!all(isfinite(p))||distance>=f.controls.w)return half4(1,0,0,0);
    float footprint;float3 normal=ssaoNormal(pixel,p,depth,f,footprint);
    float radius=f.controls.x,reach=min(radius/footprint,96.0);
    const uint directions=SSAO_QUALITY==0?8u:SSAO_QUALITY==1?6u:4u;
    const uint steps=SSAO_QUALITY==0?3u:2u;
    float occlusion=0;
    float phase=fract(52.9829189*fract(dot(floor(in.position.xy),float2(.06711056,.00583715))));
    for(uint d=0;d<directions;d++){
        float angle=(float(d)+phase)*(6.28318530718f/float(directions));float2 dir=float2(cos(angle),sin(angle));
        float horizon=0;
        for(uint s=0;s<steps;s++){
            float fraction=(float(s)+.5+phase*.5)/float(steps);fraction*=fraction;
            int2 at=int2(pixel)+int2(round(dir*max(1.0,reach*fraction)));
            // Missing offscreen samples contribute open sky; never duplicate a border occluder.
            if(any(at<0)||any(at>=int2(f.screen.xy)))continue;
            float sz=depth.read(uint2(at));if(sz<=1e-8)continue;
            float3 delta=ssaoPosition(uint2(at),sz,f)-p;float dist2=dot(delta,delta);
            if(dist2<1e-8||dist2>=radius*radius)continue;
            float dist=sqrt(dist2);
            float above=max(0.0,(dot(normal,delta)-.025)/dist-.06);
            horizon=max(horizon,above*(1-dist2/(radius*radius)));
        }
        occlusion+=horizon;
    }
    float fade=(1-smoothstep(f.controls.w*.65,f.controls.w,distance))*smoothstep(1.0,3.0,reach);
    float visibility=clamp(1-occlusion*(2.0/float(directions))*f.controls.y*fade,.35,1.0);
    return half4(float4(visibility,distance,ssaoOct(normal)));
}
fragment float4 ssao_apply_fs(SunScreenVertex in [[stage_in]],float4 color [[color(0)]],constant SsaoFrame &f [[buffer(0)]],constant SunLighting &l [[buffer(1)]],constant Fog &fog [[buffer(2)]],constant SunFrame &sun [[buffer(3)]],
    depth2d<float> depth [[texture(0)]],texture2d<half> mask [[texture(1)]]){
    uint2 pixel=uint2(in.position.xy);float z=depth.read(pixel);
    float3 p=ssaoPosition(pixel,max(z,1e-8),f);
    // Quad derivatives are enough for the reconstruction weights; robust five-tap normals stay in the half-size mask.
    float3 dx=dfdx(p),dy=dfdy(p),normal=cross(dx,dy);
    normal*=rsqrt(max(dot(normal,normal),1e-16));if(dot(normal,p)>0)normal=-normal;
    float footprint=max(min(length(dx),length(dy)),1e-5),distance=length(p);
    if(z<=1e-8||distance>=f.controls.w)return color;
    // A small bilateral footprint combines spatial denoising and upsampling in the only full-size pass.
    float2 coord=(float2(pixel)-1)*.5;int2 base=int2(round(coord));
    int2 last=int2(mask.get_width(),mask.get_height())-1;float sum=0,total=0;float2 ownOct=ssaoOct(normal);
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        half4 v=mask.read(uint2(clamp(base+int2(x,y),int2(0),last)));if(v.y<=0)continue;
        float2 normalDelta=ownOct-float2(v.zw);
        float nd=max(0.0,1-dot(normalDelta,normalDelta)*4);nd*=nd;nd*=nd;
        float difference=abs(float(v.y)-distance)/max(.04,footprint*4);
        float2 delta=float2(base+int2(x,y))-coord;
        float weight=exp2(-dot(delta,delta)*1.5)*nd/(1+difference*difference*8);
        sum+=float(v.x)*weight;total+=weight;
    }
    float visibility=total>1e-4?sum/total:1;
    // Color attachment fetch avoids a full-size scene-color copy on Apple GPUs. Preserve alpha.
    if(l.enabled){
        float environment=linear_fog_value(distance,fog.FogEnvironmentalStart,fog.FogEnvironmentalEnd);
        float render=linear_fog_value(max(length(p.xz),abs(p.y)),fog.FogRenderDistanceStart,fog.FogRenderDistanceEnd);
        SunFog air=sunFog(saturate(max(environment,render)*fog.FogColor.a),p,1.0,fog,sun,l);
        float3 scene=f.screen.w>.5?color.rgb:pow(max(color.rgb,0.0),2.2);
        scene=sunFogEditScene(scene,float3(0),1-visibility,air,l);
        color.rgb=f.screen.w>.5?scene:pow(scene,1.0/2.2);
    }else color.rgb*=f.screen.w>.5?visibility:pow(visibility,1.0/2.2);
    return color;
}
