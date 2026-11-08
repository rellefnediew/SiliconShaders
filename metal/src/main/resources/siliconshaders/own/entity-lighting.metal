// Same light, shadows and display transform as terrain, in the object's own draw: no extra passes or render targets.
// The shadow maps already contain the objects (SunEntities), so mobs shade each other and themselves. Sun-facing pixels
// call the terrain's shared sunSurfaceLighting (cascade choice, normal bias, PCF, colored glass); sun-away pixels skip it.
struct SunEntityFrame { SunFrame sun; SunBlendFrame blend; SunGlassFrame glass; };
static float3 sunEntityColor(float3 albedo,float4 overlay,float3 position,float3 vertexNormal,float2 light,bool front,float3 glint,
    constant Fog &fog,constant SunEntityFrame &frame,constant SunLighting &lighting,
    array<depth2d<float>,4> shadows,array<depth2d<float>,4> glassDepth,array<texture2d<float>,4> glassColor,device const uint *colored) {
    constant SunFrame &sun=frame.sun;
    // Derivatives run before normal selection, also for moving blocks without a normal attribute.
    float3 flat=cross(dfdx(position),dfdy(position));
    float3 normal=dot(vertexNormal,vertexNormal)>.01?normalize(vertexNormal):flat*rsqrt(max(dot(flat,flat),1e-12));
    if(dot(vertexNormal,vertexNormal)>.01){if(!front)normal=-normal;}else if(dot(normal,position)>0)normal=-normal;
    albedo=mix(overlay.rgb,albedo,overlay.a);
    float block=saturate(light.x),sky=saturate(light.y);
    float3 visible=float3(1);
    if(sun.sun.w>0&&sun.config.w>0&&dot(normal,sun.sun.xyz)>0) {
        constexpr sampler comparison(coord::normalized,address::clamp_to_edge,filter::linear,compare_func::less_equal);
        visible=sunSurfaceLighting(position,normal,float2(0),sun,frame.blend,frame.glass,shadows,glassDepth,glassColor,comparison,false);
    }
    float3 lit=sunTerrainLight(pow(max(albedo,0.0),2.2),block,sky,normal,visible,position,sun,lighting,colored);
    float distance=length(position),cylindrical=max(length(position.xz),abs(position.y));
    float environment=saturate((distance-fog.FogEnvironmentalStart)/max(fog.FogEnvironmentalEnd-fog.FogEnvironmentalStart,1e-6));
    float render=saturate((cylindrical-fog.FogRenderDistanceStart)/max(fog.FogRenderDistanceEnd-fog.FogRenderDistanceStart,1e-6));
    float amount=saturate(max(environment,render)*fog.FogColor.a);
    // Preserve the old no-fog glint result, then attenuate its contribution through the same air segment.
    SunFog air=sunFog(amount,position,1.0,fog,sun,lighting);
    float3 display=pow(max(mix(air.radiance,sunToneMap(lit,lighting),air.transmittance),0.0),1.0/2.2)+glint*air.transmittance;
    return lighting.srgb?pow(max(display,0.0),2.2):display;
}
