// Native camera post. Independent packet; all effects specialize out when disabled.
constant bool CAMERA_BLOOM [[function_constant(40)]];
constant bool CAMERA_DOF [[function_constant(41)]];
constant bool CAMERA_MOTION [[function_constant(42)]];
constant bool CAMERA_FLARE [[function_constant(43)]];
constant bool CAMERA_CHROMA [[function_constant(44)]];
constant bool CAMERA_VIGNETTE [[function_constant(45)]];
constant bool CAMERA_SHARPEN [[function_constant(46)]];
constant bool CAMERA_RAYS [[function_constant(47)]];
struct CameraFrame { float4x4 inverse,previous;float4 screen,light,lightColor;float4 p[16]; };
static float cameraP(constant CameraFrame &f,uint i){return f.p[i>>2][i&3];}
static float3 cameraLinear(float3 color,constant CameraFrame &f){return f.screen.w>.5?max(color,0.0):pow(max(color,0.0),2.2);}
static float3 cameraDisplay(float3 color,constant CameraFrame &f){return f.screen.w>.5?max(color,0.0):pow(max(color,0.0),1.0/2.2);}
static float cameraDistance(float2 uv,float z,constant CameraFrame &f){
    if(z<=0||z>1)return 1e6;float4 p=f.inverse*float4(uv*2-1,z,1);
    return all(isfinite(p))&&p.w>1e-8?clamp(dot(p.xyz/p.w,f.p[15].xyz),.001,1e6):1e6;
}
static uint2 cameraPixel(float2 uv,constant CameraFrame &f){return uint2(clamp(uv*f.screen.xy,float2(0),f.screen.xy-1));}
// A single GPU texel holds autofocus distance and light visibility. No CPU readback.
fragment float2 camera_probe_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],
    depth2d<float,access::read> depth [[texture(1)]],texture2d<float,access::read> previous [[texture(2)]]) {
    float focus=cameraP(f,10),visible=0;float2 old=f.light.w>.5?previous.read(uint2(0)).xy:float2(focus,0);
    if(CAMERA_DOF&&cameraP(f,9)>.5){
        float distances[5];const int2 offsets[5]={int2(0),int2(-3,0),int2(3,0),int2(0,-3),int2(0,3)};
        for(uint i=0;i<5;i++){uint2 pixel=uint2(clamp(int2(f.screen.xy*.5)+offsets[i],int2(0),int2(f.screen.xy)-1));float d=cameraDistance((float2(pixel)+.5)/f.screen.xy,depth.read(pixel),f);distances[i]=d>=1e5?focus:clamp(d,.25,256.0);}
        for(uint i=0;i<5;i++)for(uint j=i+1;j<5;j++)if(distances[j]<distances[i]){float t=distances[i];distances[i]=distances[j];distances[j]=t;}
        focus=distances[2];float blend=f.light.w>.5&&old.x>0?1-exp(-f.screen.z*cameraP(f,16)):1;
        focus=mix(old.x,focus,blend);
    }
    if(CAMERA_FLARE&&f.light.z>0&&all(f.light.xy>0)&&all(f.light.xy<1)){
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){float2 uv=f.light.xy+float2(x,y)*4.0/f.screen.xy;visible+=depth.read(cameraPixel(uv,f))<=1e-6?1.0/9.0:0;}
        float edge=min(min(f.light.x,f.light.y),min(1-f.light.x,1-f.light.y));visible*=smoothstep(0.0,.025,edge)*f.light.z;
    }
    if(CAMERA_FLARE){float blend=f.light.w>.5?1-exp(-f.screen.z*cameraP(f,29)):1;visible=mix(old.y,visible,blend);}
    return float2(focus,visible);
}
// Camera-motion blur along the reprojected velocity. Depth of field has its own passes below.
fragment float4 camera_blur_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],
    texture2d<float> scene [[texture(0)]],depth2d<float,access::read> depth [[texture(1)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/f.screen.xy;float4 center=scene.sample(linear,uv);float z=depth.read(cameraPixel(uv,f));
    float distance=cameraDistance(uv,z,f);float2 velocity=0;uint samples=1;
    if(CAMERA_MOTION&&f.light.w>.5&&f.screen.z>0){
        float4 position=f.inverse*float4(uv*2-1,z,1);
        if(z<=0)position=float4(position.xyz,0);else position/=max(position.w,1e-8);
        float4 old=f.previous*position;
        if(old.w>1e-8&&all(isfinite(old))){velocity=(uv-(old.xy/old.w*.5+.5))*f.screen.xy;velocity*=cameraP(f,18)*min(cameraP(f,19)*.001/f.screen.z,64.0);float magnitude=length(velocity);velocity*=min(1.0,cameraP(f,20)/max(magnitude,1e-6));}
        if(length(velocity)>.1)samples=max(samples,uint(cameraP(f,21)));
    }
    if(samples<=1)return center;samples=clamp(samples,1u,24u);
    float3 sum=cameraLinear(center.rgb,f);float weight=1;
    for(uint i=0;i<samples;i++){
        float t=(float(i)+.5)/float(samples);
        float2 at=clamp(uv+velocity*(t-.5)/f.screen.xy,.5/f.screen.xy,1-.5/f.screen.xy);float other=cameraDistance(at,depth.read(cameraPixel(at,f)),f);
        float tolerance=max(.15,distance*.03);float w=abs(other-distance)<tolerance?1.0:0.0;
        sum+=cameraLinear(scene.sample(linear,at).rgb,f)*w;weight+=w;
    }
    return float4(cameraDisplay(sum/weight,f),center.a);
}
// Depth of field at half resolution: a prefilter stores linear color and the signed circle of confusion (negative in
// front of the focus); a tile pass and a dilation record how far blur reaches around every 16x16 block, so sharp areas
// gather few samples and blurred ones many; a gather compares every sample's own blur size with its distance to the
// center (foreground blur spreads over the sharp background, background never bleeds onto the foreground, bright
// samples form bokeh); a full-resolution composite blends it with the sharp image.
// Blur follows the thin lens: |1/focus - 1/distance|, so a close focus blurs the background strongly and a far focus
// leaves it nearly sharp. The radius setting (pixels at 1080 lines) is the blur at infinity for a focus at 8 blocks with
// a 70 degree field of view; a narrower view (zoom, spyglass) blurs more. Soft cap at 3x.
static float cameraFocal(constant CameraFrame &f){
    float4 a=f.inverse*float4(0,0,.5,1),b=f.inverse*float4(0,1,.5,1);float3 u=normalize(a.xyz/a.w),v=normalize(b.xyz/b.w);
    return dot(u,v)/max(length(cross(u,v)),1e-4);
}
static float cameraCoc(float distance,constant CameraFrame &f,float focus){
    float inverse=8/max(focus,.05),x=abs(inverse-8/max(distance,.05));
    x=max(x-abs(inverse-8/(max(focus,.05)+max(cameraP(f,11),.25)*.5)),0.0); // the sharp range, in the same measure
    if(x<=0)return 0;
    x=x/sqrt(1+x*x/9);
    float radius=cameraP(f,12)*f.screen.y/1080*cameraFocal(f)/1.4281480*(distance<focus?cameraP(f,13):cameraP(f,14));
    return min(radius*x,64.0)*(distance<focus?-1:1);
}
static float cameraBokeh(float3 c){return 1+3*smoothstep(.55,1.0,dot(c,float3(.2126,.7152,.0722)));}
fragment half4 camera_dof_prefilter_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],
    texture2d<float> scene [[texture(0)]],depth2d<float,access::read> depth [[texture(1)]],texture2d<float,access::read> probe [[texture(2)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float focus=probe.read(uint2(0)).x,nearest=1e7;float3 color=0;uint2 base=uint2(in.position.xy)*2,last=uint2(f.screen.xy)-1;
    for(uint i=0;i<4;i++){
        uint2 pixel=min(base+uint2(i&1,i>>1),last);float2 at=(float2(pixel)+.5)/f.screen.xy;
        color+=cameraLinear(scene.sample(linear,at).rgb,f);nearest=min(nearest,cameraDistance(at,depth.read(pixel),f)); // nearest keeps silhouettes
    }
    return half4(half3(color*.25),half(cameraCoc(nearest,f,focus)*.5));
}
// Largest blur size in each 16x16 block of the half-resolution image, then over the 5x5 neighboring blocks (32 half
// pixels, the largest blur size).
fragment half4 camera_dof_tiles_fs(SunScreenVertex in [[stage_in]],texture2d<float> source [[texture(0)]]) {
    uint2 base=uint2(in.position.xy)*16,last=uint2(source.get_width(),source.get_height())-1;float largest=0;
    for(uint y=0;y<16;y++)for(uint x=0;x<16;x++)largest=max(largest,abs(source.read(min(base+uint2(x,y),last)).a));
    return half4(half(largest),0,0,0);
}
fragment half4 camera_dof_dilate_fs(SunScreenVertex in [[stage_in]],texture2d<float> source [[texture(0)]]) {
    int2 at=int2(in.position.xy),last=int2(source.get_width(),source.get_height())-1;float largest=0;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++)largest=max(largest,source.read(uint2(clamp(at+int2(x,y),int2(0),last))).r);
    return half4(half(largest),0,0,0);
}
fragment half4 camera_dof_gather_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],constant float4 &pass [[buffer(1)]],
    texture2d<float> source [[texture(0)]],texture2d<float> tiles [[texture(1)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/pass.xy;float4 center=source.sample(linear,uv);float own=abs(center.a);
    float reach=min(max(own,tiles.read(min(uint2(in.position.xy)/16,uint2(tiles.get_width(),tiles.get_height())-1)).r),32.0);
    float b=cameraBokeh(center.rgb);float3 sum=center.rgb*b;float total=b,cover=0;
    if(reach>=.5){
        // About one sample per 1.5 half pixels squared of the disc, up to four times the samples setting.
        uint n=clamp(uint(1.4*reach*reach),8u,clamp(uint(cameraP(f,15))*4,16u,96u));
        // Interleaved gradient noise turns the spiral rotation per pixel: fine grain instead of repeated copies.
        float spin=fract(52.9829189*fract(dot(in.position.xy,float2(.06711056,.00583715))))*6.2831853;
        for(uint i=0;i<n;i++){
            float r=sqrt((float(i)+.5)/float(n))*reach,a=float(i)*2.39996323+spin;
            float4 s=source.sample(linear,uv+float2(cos(a),sin(a))*r/pass.xy);float size=abs(s.a);
            if(s.a>center.a)size=min(size,own*2); // farther than the center: may not cover it beyond its own blur
            float m=smoothstep(r-1,r+1,size),w=m*cameraBokeh(s.rgb);
            sum+=s.rgb*w;total+=w;if(s.a<center.a)cover=max(cover,m*size);
        }
    }
    return half4(half3(sum/total),half(cover));
}
fragment float4 camera_dof_composite_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],texture2d<float> scene [[texture(0)]],
    depth2d<float,access::read> depth [[texture(1)]],texture2d<float,access::read> probe [[texture(2)]],texture2d<float> blurred [[texture(3)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/f.screen.xy;float4 sharp=scene.sample(linear,uv);
    float own=abs(cameraCoc(cameraDistance(uv,depth.read(cameraPixel(uv,f)),f),f,probe.read(uint2(0)).x))*.5;
    float2 t=.5/float2(blurred.get_width(),blurred.get_height()); // 4-tap tent smooths the gather's grain
    float4 soft=(blurred.sample(linear,uv+t)+blurred.sample(linear,uv-t)+blurred.sample(linear,uv+float2(t.x,-t.y))+blurred.sample(linear,uv+float2(-t.x,t.y)))*.25;
    float amount=smoothstep(.2,1.0,max(own,soft.a));
    return float4(cameraDisplay(mix(cameraLinear(sharp.rgb,f),soft.rgb,amount),f),sharp.a);
}
// Render-target passes preserve Apple GPU texture compression, also on M1.
// Bloom pyramid: 13-tap downsample (Karis average on the first level against single-pixel flicker), 9-tap tent upsample
// added onto the next larger level. Every level keeps its energy, so the wide levels give a visible soft glow.
static float3 cameraBloomSample(texture2d<float> source,sampler linear,float2 uv,constant CameraFrame &f,bool first){float3 color=source.sample(linear,uv).rgb;return first?cameraLinear(color,f):color;}
static float cameraKaris(float3 c){return 1/(1+dot(c,float3(.2126,.7152,.0722)));}
fragment half4 camera_bloom_down_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],constant float4 &pass [[buffer(1)]],texture2d<float> source [[texture(0)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/pass.xy,t=1/float2(source.get_width(),source.get_height());bool first=pass.w>.5;
    #define S(x,y) cameraBloomSample(source,linear,uv+float2(x,y)*t,f,first)
    float3 a=S(-2,-2),b=S(0,-2),c=S(2,-2),d=S(-1,-1),e=S(1,-1),g=S(-2,0),h=S(0,0),i=S(2,0),j=S(-1,1),k=S(1,1),l=S(-2,2),m=S(0,2),n=S(2,2);
    #undef S
    float3 groups[5]={(d+e+j+k)*.25,(a+b+g+h)*.25,(b+c+h+i)*.25,(g+h+l+m)*.25,(h+i+m+n)*.25};
    float weights[5]={.5,.125,.125,.125,.125};float3 color=0;float total=0;
    for(int q=0;q<5;q++){float w=weights[q]*(first?cameraKaris(groups[q]):1.0);color+=groups[q]*w;total+=w;}
    color/=total;
    if(first){
        float brightness=max(max(color.r,color.g),color.b),luma=dot(color,float3(.2126,.7152,.0722)),threshold=cameraP(f,3),knee=max(cameraP(f,4),.001);
        float soft=clamp(brightness-threshold+knee,0.0,2*knee);soft=soft*soft/(4*knee);color*=max(brightness-threshold,soft)/max(brightness,1e-6);
        // The input is already tone-mapped, so a torch is barely brighter than the sky. Near-white light sources (torches,
        // glowstone, sun, lanterns) get their lost range back; saturated colors such as the blue sky keep their contribution.
        color*=1+6*smoothstep(.6,1.0,luma);
    }
    return half4(half3(color),0);
}
fragment half4 camera_bloom_up_fs(SunScreenVertex in [[stage_in]],constant float4 &pass [[buffer(1)]],texture2d<float> source [[texture(0)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/pass.xy,d=pass.z/float2(source.get_width(),source.get_height());
    float3 color=source.sample(linear,uv).rgb*4;
    color+=(source.sample(linear,uv+float2(d.x,0)).rgb+source.sample(linear,uv-float2(d.x,0)).rgb+source.sample(linear,uv+float2(0,d.y)).rgb+source.sample(linear,uv-float2(0,d.y)).rgb)*2;
    color+=source.sample(linear,uv+d).rgb+source.sample(linear,uv-d).rgb+source.sample(linear,uv+float2(d.x,-d.y)).rgb+source.sample(linear,uv+float2(-d.x,d.y)).rgb;
    return half4(half3(color/16),0);
}
// Lens flare in screen space, measured in units of the image height: glare and starburst at the source, aperture-shaped
// ghosts mirrored through the image center with slight color fringes, a halo ring around the center on the light's side,
// and an anamorphic streak.
static float cameraHex(float2 q){q=abs(q);return max(q.x*.8660254+q.y*.5,q.y);}
static float3 cameraFlare(float2 uv,constant CameraFrame &f,float visibility){
    if(visibility<=.001)return 0;
    float aspect=f.screen.x/f.screen.y;float2 p=(uv-.5)*float2(aspect,1),light=(f.light.xy-.5)*float2(aspect,1);
    float3 tint=f.lightColor.rgb,result=0;float size=cameraP(f,26),spacing=cameraP(f,25)/.4;uint ghosts=clamp(uint(cameraP(f,24)),1u,8u);
    // Glare: tight core, long soft tail and a starburst that turns slowly with the source's screen position.
    float2 rel=p-light;float dist=length(rel),angle=atan2(rel.y,rel.x)+light.x*1.3;
    float core=exp(-dist*dist/(size*size*.08)),tail=size*size*.02/(dist*dist+size*size*.02);
    float rays=pow(abs(cos(angle*3)),48.0)+.6*pow(abs(cos(angle*5+.7)),64.0);
    result+=tint*(core*1.2+tail*.7+rays*exp(-dist/(size*1.1))*.3);
    // Ghosts: positions along the light-center axis (negative: across the center), sizes, shapes and hues per ghost.
    const float along[8]={-.38,-.72,.42,-1.08,-.18,-1.55,.22,-2.05};
    const float scale[8]={.9,.45,.3,1.5,.6,.8,.22,1.15};
    const float3 hue[8]={float3(.55,.85,1),float3(1,.7,.4),float3(.6,1,.7),float3(.75,.6,1),float3(1,.9,.6),float3(.5,.75,1),float3(1,.6,.7),float3(.7,.95,.85)};
    for(uint i=0;i<ghosts;i++){
        float2 center=light*along[i]*spacing;float r=max(size*scale[i],.002);float3 ghost;
        for(int c=0;c<3;c++){
            float2 q=(p-center)/(r*(1+.035*float(c-1)));float d=(i&1)?cameraHex(q):length(q);
            ghost[c]=smoothstep(1.0,.94,d)*(.3+.7*smoothstep(.6,1.0,d));
        }
        float fade=smoothstep(1.3,.2,length(center))*(.6+.4*smoothstep(.05,.5,length(light)));
        result+=ghost*mix(tint,hue[i],.6)*fade*.22;
    }
    // Halo: thin chromatic ring around the image center, strongest on the light's side.
    if(cameraP(f,27)>0){
        float radius=.42+.08*length(light),side=pow(saturate(dot(normalize(p+1e-6),normalize(light+1e-6))*.5+.5),3.0);float3 ring;
        for(int c=0;c<3;c++){float d=(length(p)-radius*(1+.025*float(1-c)))/.022;ring[c]=exp(-d*d);}
        result+=ring*side*cameraP(f,27)*.9*mix(tint,float3(.8,.9,1),.5);
    }
    // Anamorphic streak through the source.
    if(cameraP(f,28)>0){
        float rot=cameraP(f,31)*.0174532925;float2 axis=float2(cos(rot),sin(rot));
        float across=dot(rel,float2(-axis.y,axis.x)),length_=abs(dot(rel,axis));
        float streak=exp(-across*across/.000006)*exp(-length_*1.4)+.35*exp(-across*across/.00012)*exp(-length_*3.5);
        result+=streak*cameraP(f,28)*mix(tint,float3(.55,.72,1),.55);
    }
    return result*cameraP(f,23)*visibility;
}
// Low-resolution volumetric integration. Samples actual world-space shadow coverage, also off-screen lights.
struct CameraRaysFrame {float4x4 clips[4];float4 bias,light,info;};
static float cameraRayEnd(float2 uv,float z,constant CameraFrame &f){
    float maximum=cameraP(f,51);if(z<=0||z>=1)return maximum;
    float4 point=f.inverse*float4(uv*2-1,z,1);
    return point.w>1e-8&&all(isfinite(point))?min(maximum,length(point.xyz/point.w)):maximum;
}
static float cameraRayShadow(float3 p,constant CameraRaysFrame &r,array<depth2d<float>,4> maps,sampler comparison){
    uint count=uint(r.info.x);
    for(uint i=0;i<count;i++){
        float3 q=(r.clips[i]*float4(p,1)).xyz;float edge=max(abs(q.x),abs(q.y));
        if(edge>=.97||q.z<=0||q.z>=1)continue;
        float visibility=maps[i].sample_compare(comparison,q.xy*.5+.5,q.z-r.bias[i]);
        if(i+1<count&&edge>.80){
            float3 next=(r.clips[i+1]*float4(p,1)).xyz;
            if(all(abs(next.xy)<.97)&&next.z>0&&next.z<1)
                visibility=mix(visibility,maps[i+1].sample_compare(comparison,next.xy*.5+.5,next.z-r.bias[i+1]),smoothstep(.80,.97,edge));
        }
        if(i+1==count)visibility*=1-smoothstep(.85,.97,edge);
        return visibility;
    }
    return 0; // missing map coverage cannot authorize illuminated fog
}
fragment half4 camera_rays_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],
    constant CameraRaysFrame &r [[buffer(1)]],constant SunLighting &lighting [[buffer(2)]],
    depth2d<float,access::read> depth [[texture(1)]],array<depth2d<float>,4> maps [[texture(20)]]){
    constexpr sampler comparison(coord::normalized,address::clamp_to_edge,filter::linear,compare_func::less_equal);
    float2 uv=in.position.xy/r.info.yz;float z=depth.read(cameraPixel(uv,f)),end=cameraRayEnd(uv,z,f);
    // Use two reconstructed points: view-bobbing translation must not tilt the ray toward a near-plane point.
    float4 near=f.inverse*float4(uv*2-1,1,1),far=f.inverse*float4(uv*2-1,.125,1);
    if(near.w<=1e-8||far.w<=1e-8)return half4(0,0,0,half(end));
    float3 a=near.xyz/near.w,b=far.xyz/far.w,ray=normalize(b-a),origin=a-ray*dot(a,ray);
    float limit=max(0.0,end-max(.05,end*.001)),step=limit/clamp(cameraP(f,52),8.0,64.0);
    float jitter=fract(52.9829189*fract(dot(floor(in.position.xy),float2(.06711056,.00583715))));
    float density=cameraP(f,50)*(1+lighting.env.y*.8)*(r.info.w>.5?2.5:1.0),sum=0;
    uint samples=uint(clamp(cameraP(f,52),8.0,64.0));
    for(uint i=0;i<samples;i++){
        float distance=(float(i)+jitter)*step;
        sum+=cameraRayShadow(origin+ray*distance,r,maps,comparison)*exp(-density*distance)*density*step;
    }
    float g=cameraP(f,54),mu=dot(ray,r.light.xyz),phase=(1-g*g)/max(pow(1+g*g-2*g*mu,1.5),.02)*.07957747;
    float3 color=lighting.moon>.5?sunTuned(lighting.p[17]):mix(sunTuned(lighting.p[1]),sunTuned(lighting.p[0]),sunNoon(r.light.y));
    color*=lighting.exposure;float luma=dot(color,float3(.2126,.7152,.0722));color=max(mix(float3(luma),color,lighting.saturation),0.0);
    float strength=cameraP(f,49)*r.light.w*(lighting.moon>.5?cameraP(f,55):1.0);
    if(r.info.w>.5){color*=float3(.22,.62,.72);strength*=cameraP(f,56);}
    return half4(half3(max(color*sum*phase*strength,0.0)),half(end));
}
// Four depth-weighted taps keep a low-resolution beam behind thin foreground silhouettes.
static float3 cameraRayUpsample(float2 uv,constant CameraFrame &f,texture2d<float,access::read> rays,depth2d<float,access::read> depth){
    float distance=cameraRayEnd(uv,depth.read(cameraPixel(uv,f)),f),tolerance=max(.20,distance*.025);
    float2 pixel=uv*float2(rays.get_width(),rays.get_height())-.5,weight=fract(pixel);int2 base=int2(floor(pixel));
    float3 sum=0;float total=0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
        uint2 at=uint2(clamp(base+int2(x,y),int2(0),int2(rays.get_width()-1,rays.get_height()-1)));
        float4 sample=rays.read(at);float w=(x?weight.x:1-weight.x)*(y?weight.y:1-weight.y);
        w*=exp(-abs(sample.a-distance)/tolerance);sum+=sample.rgb*w;total+=w;
    }
    return total>1e-4?sum/total:float3(0);
}
static float3 cameraDecorate(float3 color,float2 uv,constant CameraFrame &f,texture2d<float> bloom,texture2d<float,access::read> probe,texture2d<float,access::read> rays,depth2d<float,access::read> depth){
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    if(CAMERA_RAYS)color+=cameraRayUpsample(uv,f,rays,depth);
    if(CAMERA_BLOOM)color+=bloom.sample(linear,uv).rgb*cameraP(f,2);
    if(CAMERA_FLARE)color+=cameraFlare(uv,f,probe.read(uint2(0)).y);
    if(CAMERA_VIGNETTE){float2 p=abs((uv-.5)*2);float radius=mix(max(p.x,p.y),length(p)*.70710678,cameraP(f,40));float amount=smoothstep(cameraP(f,38),cameraP(f,38)+cameraP(f,39),radius)*cameraP(f,37);color=mix(color,pow(float3(cameraP(f,41),cameraP(f,42),cameraP(f,43)),2.2),amount);}
    return color;
}
fragment float4 camera_composite_fs(SunScreenVertex in [[stage_in]],constant CameraFrame &f [[buffer(0)]],texture2d<float> scene [[texture(0)]],texture2d<float> bloom [[texture(3)]],texture2d<float,access::read> probe [[texture(2)]],texture2d<float,access::read> rays [[texture(4)]],depth2d<float,access::read> depth [[texture(1)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/f.screen.xy;float4 source=scene.sample(linear,uv);float3 color=cameraLinear(source.rgb,f);
    if(CAMERA_CHROMA){
        // Displacement is in pixels at 1080 lines (Retina screens shift twice as many), spread over a smooth spectrum.
        float aspect=f.screen.x/f.screen.y;float2 p=(uv-.5)*2;float r=length(p*float2(aspect,1))/length(float2(aspect,1));
        float amount=pow(saturate((r-cameraP(f,34))/max(1-cameraP(f,34),.01)),cameraP(f,35));
        float2 offset=normalize(p+1e-8)*cameraP(f,33)*amount*(f.screen.y/1080)/f.screen.xy;
        if(amount>0){
            float3 sum=0,weight=0;
            for(int i=0;i<7;i++){
                float s=float(i)/6;float3 w=float3(smoothstep(.3,1.0,s),1-abs(s*2-1),1-smoothstep(0.0,.7,s));
                sum+=cameraLinear(scene.sample(linear,uv+offset*(s*2-1)).rgb,f)*w;weight+=w;
            }
            color=sum/weight;
        }
    }
    if(CAMERA_SHARPEN){float2 d=cameraP(f,46)/f.screen.xy;float3 neighbors=(cameraLinear(scene.sample(linear,uv+float2(d.x,0)).rgb,f)+cameraLinear(scene.sample(linear,uv-float2(d.x,0)).rgb,f)+cameraLinear(scene.sample(linear,uv+float2(0,d.y)).rgb,f)+cameraLinear(scene.sample(linear,uv-float2(0,d.y)).rgb,f))*.25;color+=clamp((color-neighbors)*cameraP(f,45),-cameraP(f,47),cameraP(f,47));}
    return float4(cameraDisplay(cameraDecorate(max(color,0.0),uv,f,bloom,probe,rays,depth),f),source.a);
}
// Only same-pixel operations: framebuffer fetch avoids a full-size source copy on Apple GPUs.
fragment float4 camera_composite_fetch_fs(SunScreenVertex in [[stage_in]],float4 prior [[color(0)]],constant CameraFrame &f [[buffer(0)]],texture2d<float> bloom [[texture(3)]],texture2d<float,access::read> probe [[texture(2)]],texture2d<float,access::read> rays [[texture(4)]],depth2d<float,access::read> depth [[texture(1)]]) {
    float2 uv=in.position.xy/f.screen.xy;return float4(cameraDisplay(cameraDecorate(cameraLinear(prior.rgb,f),uv,f,bloom,probe,rays,depth),f),prior.a);
}
