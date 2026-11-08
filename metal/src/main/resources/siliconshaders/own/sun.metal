// Native Metal sunlight: compact real-quad shadow draws, with record draws as a fallback.
// args 12-15 are the alpha-tested draw when opaque casters skip the fragment stage.
kernel void sun_reset(device uint *args [[buffer(3)]],uint i [[thread_position_in_grid]]) {
    if(i<4) args[i]=i==0?384u:0u;
    if(i>=12 && i<16) args[i]=i==12?384u:0u;
}
// One non-instanced draw over real quads, rather than 64 padded quads per record.
kernel void sun_reset_exact(device uint *args [[buffer(3)]],uint i [[thread_position_in_grid]]) {
    if(i<4) args[i]=i==1?1u:0u;
    if(i>=12 && i<16) args[i]=i==13?1u:0u;
}

struct SunCullFrame { uint count;float nearRadius;uint2 pad;float4 planes[16]; };
struct SunSurfaceCull { uint enabled;float guard,nearRadius;uint candidateCount;uint4 grid; };
// A light-space occupancy/depth grid per cascade. Zero means no receiver;
// header=1 means this level failed open. Light depth increases away from the sun.
constant uint SUN_RECEIVER_SIDE=64, SUN_RECEIVER_STRIDE=4097;
struct SunReceiverFrame { float4x4 inverse,view;float4x4 clips[4];float4 guards;uint4 config; };
static void sunMarkReceiver(device atomic_uint *bins,uint level,float3 lo,float3 hi,float guard) {
    uint base=level*SUN_RECEIVER_STRIDE;
    if(!all(isfinite(lo))||!all(isfinite(hi))) {atomic_store_explicit(bins+base,1u,memory_order_relaxed);return;}
    lo.xy-=guard;hi.xy+=guard;
    // The comparison reference includes the clamped receiver-plane gradient.
    // Its depth excursion over the PCF footprint must be bounded as well.
    hi.z+=2*guard+.001;
    if(any(lo.xy>1)||any(hi.xy< -1)||hi.z<0||lo.z>1)return;
    int2 first=clamp(int2(floor((lo.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    int2 last=clamp(int2(floor((hi.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    uint farthest=as_type<uint>(clamp(hi.z,1e-8,1.0));
    for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++)
        atomic_fetch_max_explicit(bins+base+1+y*SUN_RECEIVER_SIDE+x,farthest,memory_order_relaxed);
}
kernel void sun_receivers_reset(device uint *bins [[buffer(0)]],device uint *stats [[buffer(1)]],uint i [[thread_position_in_grid]]) {
    if(i<4*SUN_RECEIVER_STRIDE)bins[i]=0;
    if(i<32)stats[i]=0;
}
kernel void sun_receivers_mark(constant SunReceiverFrame &f [[buffer(0)]],device atomic_uint *bins [[buffer(1)]],
    depth2d<float,access::read> depth [[texture(0)]],texture2d<float,access::read> normals [[texture(1)]],
    uint2 pixel [[thread_position_in_grid]],uint lane [[thread_index_in_simdgroup]]) {
    bool valid=all(pixel<f.config.xy);float3 position=float3(0);uint bad=0;
    if(valid) {
        valid=normals.read(pixel).w>=0;
        if(valid) {
            float z=depth.read(pixel);float2 uv=(float2(pixel)+.5)/float2(f.config.xy);
            float4 p=f.inverse*float4(uv*2-1,z,1);
            if(!all(isfinite(p))||p.w<=1e-8||z<=0||z>1){bad=1;valid=false;}
            else position=p.xyz/p.w;
        }
    }
    bad=simd_max(bad);
    for(uint level=0;level<f.config.z;level++) {
        float infinity=as_type<float>(0x7f800000u);
        float3 q=(f.clips[level]*float4(position,1)).xyz;
        float3 lo=valid?q:float3(infinity),hi=valid?q:float3(-infinity);
        lo=float3(simd_min(lo.x),simd_min(lo.y),simd_min(lo.z));
        hi=float3(simd_max(hi.x),simd_max(hi.y),simd_max(hi.z));
        if(bad) {
            if(lane==0)atomic_store_explicit(bins+level*SUN_RECEIVER_STRIDE,1u,memory_order_relaxed);
        } else if(all(lo<=hi)) {
            if(!all(isfinite(lo))||!all(isfinite(hi))) {
                if(lane==0)atomic_store_explicit(bins+level*SUN_RECEIVER_STRIDE,1u,memory_order_relaxed);
            } else {
                float guard=f.guards[level];
                int2 first=clamp(int2(floor(((lo.xy-guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                int2 last=clamp(int2(floor(((hi.xy+guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                uint cells=uint(last.x-first.x+1)*uint(last.y-first.y+1);
                if(cells<=64) {
                    if(lane==0)sunMarkReceiver(bins,level,lo,hi,guard);
                } else {
                    // Separate opposite sides of a large near/far discontinuity.
                    // Both subsets stay conservative, but do not fill their empty gap.
                    uint axis=hi.x-lo.x>hi.y-lo.y?0u:1u;
                    float middle=(lo[axis]+hi[axis])*.5;
                    for(uint subset=0;subset<2;subset++) {
                        bool member=valid && uint(q[axis]>middle)==subset;
                        float3 groupLo=member?q:float3(infinity),groupHi=member?q:float3(-infinity);
                        groupLo=float3(simd_min(groupLo.x),simd_min(groupLo.y),simd_min(groupLo.z));
                        groupHi=float3(simd_max(groupHi.x),simd_max(groupHi.y),simd_max(groupHi.z));
                        if(all(groupLo<=groupHi)) {
                            int2 a=clamp(int2(floor(((groupLo.xy-guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                            int2 b=clamp(int2(floor(((groupHi.xy+guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                            uint area=uint(b.x-a.x+1)*uint(b.y-a.y+1);
                            if(area<=64) {
                                if(lane==0)sunMarkReceiver(bins,level,groupLo,groupHi,guard);
                            } else if(member) {
                                // Bound remaining outliers by their own PCF footprint.
                                // At supported sizes that footprint is at most 5x5 cells.
                                sunMarkReceiver(bins,level,q,q,guard);
                            }
                        }
                    }
                }
            }
        }
    }
}
// Later water/glass draws are receivers too. Stamp camera-visible translucent
// record boxes, including those in front of opaque depth. No CPU depth readback.
kernel void sun_receivers_transparent(constant SunReceiverFrame &f [[buffer(0)]],device atomic_uint *bins [[buffer(1)]],
    constant SunFrame &sun [[buffer(2)]],device const Section *sections [[buffer(3)]],device const Rec *recs [[buffer(4)]],
    device const uint *candidates [[buffer(5)]],uint index [[thread_position_in_grid]]) {
    if(index>=f.config.w)return;
    uint slot=(sun.enabled&4u)?candidates[index]:index;if(slot>=sun.sectionCount)return;Section s=sections[slot];
    float3 origin=float3(int3(s.x,s.y,s.z)-sun.camBlock.xyz)+sun.camOffset.xyz;
    for(uint at=s.recStart;at<s.recStart+s.recCount&&at<sun.listCapacity;at++) {
        Rec r=recs[at];if(((r.meta>>9)&3)<=1)continue;
        float3 lo=origin+float3(int(r.lo&255)-16,int((r.lo>>8)&255)-16,int((r.lo>>16)&255)-16);
        float3 hi=origin+float3(int(r.hi&255)-16,int((r.hi>>8)&255)-16,int((r.hi>>16)&255)-16);
        // Homogeneous corner tests retain boxes crossing the camera/near plane.
        bool outside[6]={true,true,true,true,true,true};
        for(uint c=0;c<8;c++) {
            float3 p=float3((c&1)?hi.x:lo.x,(c&2)?hi.y:lo.y,(c&4)?hi.z:lo.z);
            float4 q=f.view*float4(p,1);
            outside[0]&=q.x< -q.w;outside[1]&=q.x>q.w;outside[2]&=q.y< -q.w;
            outside[3]&=q.y>q.w;outside[4]&=q.z<0;outside[5]&=q.z>q.w;
        }
        if(outside[0]||outside[1]||outside[2]||outside[3]||outside[4]||outside[5])continue;
        for(uint level=0;level<f.config.z;level++) {
            float3 center=(lo+hi)*.5,extent=(hi-lo)*.5;
            float3 q=(f.clips[level]*float4(center,1)).xyz;
            float3 e=abs(f.clips[level][0].xyz)*extent.x+abs(f.clips[level][1].xyz)*extent.y+abs(f.clips[level][2].xyz)*extent.z;
            sunMarkReceiver(bins,level,q-e,q+e,f.guards[level]);
        }
    }
}
// Translucent record lo/hi are placeholders. Game frames pass the real cached
// mesh box once per section instead of stamping each sorted record's huge cube.
struct SunReceiverBox { float4 lo,hi; };
kernel void sun_receivers_transparent_bounds(constant SunReceiverFrame &f [[buffer(0)]],device atomic_uint *bins [[buffer(1)]],
    device const SunReceiverBox *receivers [[buffer(2)]],uint index [[thread_position_in_grid]]) {
    if(index>=f.config.w)return;
    float3 lo=receivers[index].lo.xyz,hi=receivers[index].hi.xyz;
    if(!all(isfinite(lo))||!all(isfinite(hi))||any(lo>hi)) {
        for(uint level=0;level<f.config.z;level++)atomic_store_explicit(bins+level*SUN_RECEIVER_STRIDE,1u,memory_order_relaxed);
        return;
    }
    bool outside[6]={true,true,true,true,true,true};
    for(uint c=0;c<8;c++) {
        float3 p=float3((c&1)?hi.x:lo.x,(c&2)?hi.y:lo.y,(c&4)?hi.z:lo.z);
        float4 q=f.view*float4(p,1);
        outside[0]&=q.x< -q.w;outside[1]&=q.x>q.w;outside[2]&=q.y< -q.w;
        outside[3]&=q.y>q.w;outside[4]&=q.z<0;outside[5]&=q.z>q.w;
    }
    if(outside[0]||outside[1]||outside[2]||outside[3]||outside[4]||outside[5])return;
    float3 center=(lo+hi)*.5,extent=(hi-lo)*.5;
    for(uint level=0;level<f.config.z;level++) {
        float3 q=(f.clips[level]*float4(center,1)).xyz;
        float3 e=abs(f.clips[level][0].xyz)*extent.x+abs(f.clips[level][1].xyz)*extent.y+abs(f.clips[level][2].xyz)*extent.z;
        sunMarkReceiver(bins,level,q-e,q+e,f.guards[level]);
    }
}
static bool sunReceiverVisible(constant SunFrame &f,device const uint *bins,constant SunSurfaceCull &surface,float3 lo,float3 hi) {
    if(!surface.enabled)return true;
    uint base=surface.grid.x*SUN_RECEIVER_STRIDE;if(bins[base])return true;
    float3 center=(lo+hi)*.5,extent=(hi-lo)*.5;
    float3 q=(f.clip*float4(center,1)).xyz;
    float3 e=abs(f.clip[0].xyz)*extent.x+abs(f.clip[1].xyz)*extent.y+abs(f.clip[2].xyz)*extent.z;
    if(!all(isfinite(q))||!all(isfinite(e)))return true;
    float3 minimum=q-e,maximum=q+e;
    if(any(minimum.xy>1)||any(maximum.xy< -1))return false;
    int2 first=clamp(int2(floor((minimum.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    int2 last=clamp(int2(floor((maximum.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        uint z=bins[base+1+y*SUN_RECEIVER_SIDE+x];
        if(z && minimum.z<=as_type<float>(z))return true;
    }
    return false;
}
static bool sunCasterVisible(constant SunCullFrame &cull,float3 lo,float3 hi) {
    if(cull.count==0||any(lo>hi))return true;
    float3 nearest=clamp(float3(0),lo,hi);
    if(dot(nearest,nearest)<=cull.nearRadius*cull.nearRadius)return true;
    float3 center=(lo+hi)*0.5,extent=(hi-lo)*0.5;
    for(uint i=0;i<cull.count;i++) {
        float4 p=cull.planes[i];
        if(dot(p.xyz,center)+p.w+dot(abs(p.xyz),extent)<0)return false;
    }
    return true;
}

// surface.grid.y splits alpha-tested records from opaque ones. Opaque entries fill the
// list from the front (args 0-3), cutout entries from the back (args 12-15), so opaque
// casters can use a depth-only pipeline. Both regions share the existing worst-case list.
// surface.grid.z: this frame feeds the sparse console diagnostics. Other frames skip the shared
// counters, which every selection thread would otherwise update atomically.
static bool sunStats(constant SunSurfaceCull &surface) { return surface.enabled && surface.grid.z!=0; }
static bool sunCutoutSplit(constant SunSurfaceCull &surface,uint meta) { return surface.grid.y!=0 && ((meta>>9)&3)==1; }
template <bool Exact>
static void sunEmit(constant SunFrame &f,device atomic_uint *args,device uint *list,uint at,uint meta,bool cutout) {
    if(Exact) {
        // One reservation per record; each thread fills its own contiguous range.
        // Native allocation reserves 64 entries per possible record and bounds the
        // total vertex count to uint32. The section table owns disjoint record ranges.
        uint quads=(meta&63u)+1u;
        uint first=atomic_fetch_add_explicit(args+(cutout?12:0),quads*6u,memory_order_relaxed)/6u;
        if(cutout) { uint last=f.listCapacity*64u-1u;for(uint q=0;q<quads;q++)list[last-(first+q)]=(at<<6)|q; }
        else for(uint q=0;q<quads;q++)list[first+q]=(at<<6)|q;
    } else {
        uint index=atomic_fetch_add_explicit(args+(cutout?13:1),1,memory_order_relaxed);
        if(index<f.listCapacity) list[cutout?f.listCapacity-1u-index:index]=at;
    }
}
template <bool Exact>
static void sunCull(constant SunFrame &f,device const Section *sections,device const Rec *recs,
    device atomic_uint *args,device uint *list,constant SunCullFrame &casterCull,
    device const uint *surfaceBounds,constant SunSurfaceCull &surface,device const uint *candidates,device atomic_uint *stats,uint slot) {
    if(surface.candidateCount!=0xffffffffu) {if(slot>=surface.candidateCount)return;slot=candidates[slot];}
    if(slot>=f.sectionCount) return;
    Section s=sections[slot];if(s.recCount==0) return;
    float3 origin=float3(int3(s.x,s.y,s.z)-f.camBlock.xyz)+f.camOffset.xyz;
    float3 center=(f.clip*float4(origin+8.0,1)).xyz;
    float margin=28.0/f.config.x;
    if(any(abs(center.xy)>1.0+margin)||center.z < -margin||center.z>1.0+margin) return;
    if(!sunCasterVisible(casterCull,origin,origin+16.0))return;
    if(sunStats(surface))atomic_fetch_add_explicit(stats,1u,memory_order_relaxed);
    if(!sunReceiverVisible(f,surfaceBounds,surface,origin,origin+16.0)) {
        if(sunStats(surface))atomic_fetch_add_explicit(stats+1,1u,memory_order_relaxed);return;
    }
    uint tested=0,rejected=0,emitted=0;
    for(uint at=s.recStart;at<s.recStart+s.recCount && at<f.listCapacity;at++) {
        if(((recs[at].meta>>9)&3)>1) continue;
        if(casterCull.count || surface.enabled) {
            uint lo=recs[at].lo,hi=recs[at].hi;
            float3 minimum=float3(int(lo&255)-16,int((lo>>8)&255)-16,int((lo>>16)&255)-16);
            float3 maximum=float3(int(hi&255)-16,int((hi>>8)&255)-16,int((hi>>16)&255)-16);
            if(!sunCasterVisible(casterCull,origin+minimum,origin+maximum))continue;
            tested++;
            if(!sunReceiverVisible(f,surfaceBounds,surface,origin+minimum,origin+maximum)){rejected++;continue;}
        }
        uint meta=recs[at].meta;emitted+=(meta&63u)+1u;
        sunEmit<Exact>(f,args,list,at,meta,sunCutoutSplit(surface,meta));
    }
    if(sunStats(surface)) {
        atomic_fetch_add_explicit(stats+2,tested,memory_order_relaxed);
        atomic_fetch_add_explicit(stats+3,rejected,memory_order_relaxed);
        atomic_fetch_add_explicit(stats+4,emitted,memory_order_relaxed);
    }
}

#define SUN_CULL_PARAMS constant SunFrame &f [[buffer(0)]],device const Section *sections [[buffer(1)]], \
    device const Rec *recs [[buffer(2)]],device atomic_uint *args [[buffer(3)]],device uint *list [[buffer(4)]], \
    constant SunCullFrame &casterCull [[buffer(5)]],device const uint *surfaceBounds [[buffer(6)]], \
    constant SunSurfaceCull &surface [[buffer(7)]],device const uint *candidates [[buffer(8)]], \
    device atomic_uint *stats [[buffer(9)]],uint slot [[thread_position_in_grid]]
kernel void sun_cull(SUN_CULL_PARAMS) { sunCull<false>(f,sections,recs,args,list,casterCull,surfaceBounds,surface,candidates,stats,slot); }
kernel void sun_cull_exact(SUN_CULL_PARAMS) { sunCull<true>(f,sections,recs,args,list,casterCull,surfaceBounds,surface,candidates,stats,slot); }
#undef SUN_CULL_PARAMS

struct SunVertex { float4 pos [[position]];float2 uv;float alpha;uint layer [[flat]]; };
static SunVertex sunVertex(uint c,uint q,Rec r,constant SunFrame &f,
    device const void *arena,device const Section *sections) {
    SunVertex o={};
    Section s=sections[r.slot];Vtx v;
    uint index=r.quadStart+q;
    if(OWN_REC && COMPACT) {
        v=ownQrec(arena,index,c);
        v.pos=packed_float3(ownWindPosition(float3(v.pos),int3(s.x,s.y,s.z),
            ((device const uint *)arena)[index*10],c,float2(f.camOffset.w,as_type<float>(f.camBlock.w))));
    }
    else if(COMPACT) {
        CVtx k=((device const CVtx *)arena)[index*4+c];
        v.pos=packed_float3(OWN_POS3(arena,k.px,k.py,k.pz));v.uv0=packed_float2(float2(k.u,k.v)/65536.0);v.color=k.color;
    } else v=((device const Vtx *)arena)[index*4+c];
    float3 position=float3(v.pos)+float3(int3(s.x,s.y,s.z)-f.camBlock.xyz)+f.camOffset.xyz;
    o.pos=f.clip*float4(position,1);o.pos.y=-o.pos.y;
    o.uv=float2(v.uv0);o.alpha=float(v.color.a)/255.0;o.layer=(r.meta>>9)&3;return o;
}
vertex SunVertex sun_vs(uint vid [[vertex_id]],uint iid [[instance_id]],constant SunFrame &f [[buffer(0)]],
    device const void *arena [[buffer(1)]],device const Section *sections [[buffer(2)]],
    device const Rec *recs [[buffer(3)]],device const uint *list [[buffer(4)]]) {
    uint q=vid/6;Rec r=recs[list[iid]];
    if(q>(r.meta&63)) { SunVertex o={};o.pos=float4(0,0,-2,1);return o; }
    return sunVertex((0x032210u>>((vid%6)*4))&3,q,r,f,arena,sections);
}
vertex SunVertex sun_exact_vs(uint vid [[vertex_id]],constant SunFrame &f [[buffer(0)]],
    device const void *arena [[buffer(1)]],device const Section *sections [[buffer(2)]],
    device const Rec *recs [[buffer(3)]],device const uint *list [[buffer(4)]]) {
    uint entry=list[vid/6];
    return sunVertex((0x032210u>>((vid%6)*4))&3,entry&63u,recs[entry>>6],f,arena,sections);
}
// Alpha-tested records of the split draw, read from the end of the same list.
vertex SunVertex sun_cutout_vs(uint vid [[vertex_id]],uint iid [[instance_id]],constant SunFrame &f [[buffer(0)]],
    device const void *arena [[buffer(1)]],device const Section *sections [[buffer(2)]],
    device const Rec *recs [[buffer(3)]],device const uint *list [[buffer(4)]]) {
    uint q=vid/6;Rec r=recs[list[f.listCapacity-1u-iid]];
    if(q>(r.meta&63)) { SunVertex o={};o.pos=float4(0,0,-2,1);return o; }
    return sunVertex((0x032210u>>((vid%6)*4))&3,q,r,f,arena,sections);
}
vertex SunVertex sun_exact_cutout_vs(uint vid [[vertex_id]],constant SunFrame &f [[buffer(0)]],
    device const void *arena [[buffer(1)]],device const Section *sections [[buffer(2)]],
    device const Rec *recs [[buffer(3)]],device const uint *list [[buffer(4)]]) {
    uint entry=list[f.listCapacity*64u-1u-vid/6];
    return sunVertex((0x032210u>>((vid%6)*4))&3,entry&63u,recs[entry>>6],f,arena,sections);
}
// Exact quads as 4 indexed corners instead of 6 list vertices. One shared uint16 index buffer
// covers SUN_INDEXED_QUADS quads per instance (corners 0,1,2,2,3,0: the list draw's diagonal);
// sun_indexed_args turns the selected vertex counts into instance counts. Corners past the
// count collapse outside the clip volume.
constant uint SUN_INDEXED_QUADS=2048;
kernel void sun_indexed_args(device uint *args [[buffer(3)]],uint i [[thread_position_in_grid]]) {
    if(i>=2)return;
    uint quads=args[i?12:0]/6u,base=i?24u:16u;
    args[base]=SUN_INDEXED_QUADS*6u;args[base+1]=(quads+SUN_INDEXED_QUADS-1u)/SUN_INDEXED_QUADS;
    args[base+2]=0;args[base+3]=0;args[base+4]=0;
}
static SunVertex sunIndexedVertex(uint vid,uint iid,bool cutout,constant SunFrame &f,device const void *arena,
    device const Section *sections,device const Rec *recs,device const uint *list,device const uint *args) {
    uint at=iid*SUN_INDEXED_QUADS+vid/4u;
    if(at>=args[cutout?12:0]/6u) { SunVertex o={};o.pos=float4(0,0,-2,1);return o; }
    uint entry=list[cutout?f.listCapacity*64u-1u-at:at];
    return sunVertex(vid&3u,entry&63u,recs[entry>>6],f,arena,sections);
}
vertex SunVertex sun_exact_indexed_vs(uint vid [[vertex_id]],uint iid [[instance_id]],constant SunFrame &f [[buffer(0)]],
    device const void *arena [[buffer(1)]],device const Section *sections [[buffer(2)]],
    device const Rec *recs [[buffer(3)]],device const uint *list [[buffer(4)]],device const uint *args [[buffer(5)]]) {
    return sunIndexedVertex(vid,iid,false,f,arena,sections,recs,list,args);
}
vertex SunVertex sun_exact_indexed_cutout_vs(uint vid [[vertex_id]],uint iid [[instance_id]],constant SunFrame &f [[buffer(0)]],
    device const void *arena [[buffer(1)]],device const Section *sections [[buffer(2)]],
    device const Rec *recs [[buffer(3)]],device const uint *list [[buffer(4)]],device const uint *args [[buffer(5)]]) {
    return sunIndexedVertex(vid,iid,true,f,arena,sections,recs,list,args);
}
fragment void sun_fs(SunVertex in [[stage_in]],texture2d<float> atlas [[texture(0)]],sampler atlasSampler [[sampler(0)]]) {
    if(in.layer==1 && atlas.sample(atlasSampler,in.uv,level(0)).a*in.alpha<0.5) discard_fragment();
}

// Original posed vertex/index buffers, shared with the normal entity draw. No model animation or lighting is repeated.
struct SunActorFrame { ulong handles[4];float4x4 model;uint4 layout;int4 draw;float4 config,boundsLo,boundsHi; };
// One small test per actor/glass batch, shared by its indirect draw.
kernel void sun_cull_actors(constant SunFrame &f [[buffer(0)]],constant SunCullFrame &cull [[buffer(5)]],
    device const uint *bins [[buffer(6)]],constant SunSurfaceCull &surface [[buffer(7)]],device atomic_uint *stats [[buffer(9)]],
    device const SunActorFrame *actors [[buffer(10)]],constant uint &count [[buffer(11)]],device uint *args [[buffer(12)]],uint index [[thread_position_in_grid]]) {
    if(index>=count)return;SunActorFrame actor=actors[index];
    bool keep=actor.draw.x>0 && sunCasterVisible(cull,actor.boundsLo.xyz,actor.boundsHi.xyz) &&
        sunReceiverVisible(f,bins,surface,actor.boundsLo.xyz,actor.boundsHi.xyz);
    if(sunStats(surface)) {
        atomic_fetch_add_explicit(stats+5,1u,memory_order_relaxed);
        if(!keep)atomic_fetch_add_explicit(stats+6,1u,memory_order_relaxed);
    }
    uint base=index*8;
    args[base]=keep?(actor.config.y>.5?uint(actor.draw.x/4)*6:uint(actor.draw.x)):0;
    args[base+1]=1;args[base+2]=0;args[base+3]=actor.config.y>.5?0:as_type<uint>(actor.draw.z);args[base+4]=0;
}
struct SunActorVertex { float4 pos [[position]];float2 uv;float4 tint;float3 world; };
vertex SunActorVertex sun_actor_vs(uint vid [[vertex_id]],constant SunFrame &f [[buffer(0)]],
    device const uchar *vertices [[buffer(1)]],constant SunActorFrame &actor [[buffer(5)]]) {
    uint index=actor.config.y>0.5?(vid/6)*4+((0x032210u>>((vid%6)*4))&3):vid;
    device const uchar *v=vertices+index*actor.layout.x;
    float3 p=(actor.model*float4(float3(*(device const packed_float3 *)(v+actor.layout.y)),1)).xyz;
    SunActorVertex o;o.pos=f.clip*float4(p,1);o.pos.y=-o.pos.y;
    o.uv=float2(*(device const packed_float2 *)(v+actor.layout.z));
    o.tint=actor.layout.w==0xffffffffu?float4(1):float4(*(device const uchar4 *)(v+actor.layout.w))/255.0;
    o.world=p;return o;
}
fragment void sun_actor_fs(SunActorVertex in [[stage_in]],constant SunActorFrame &actor [[buffer(5)]],
    texture2d<float> albedo [[texture(0)]],sampler source [[sampler(0)]]) {
    if(albedo.sample(source,in.uv,level(0)).a*in.tint.a<0.5)discard_fragment();
    float fade=1.0-smoothstep(actor.config.z*0.8,actor.config.z,length(in.world));
    float noise=fract(52.9829189*fract(dot(floor(in.pos.xy),float2(0.06711056,0.00583715))));
    if(fade<noise)discard_fragment();
}
fragment float4 sun_glass_fs(SunActorVertex in [[stage_in]],texture2d<float> albedo [[texture(0)]],sampler source [[sampler(0)]]) {
    float4 glass=albedo.sample(source,in.uv,level(0))*in.tint;
    if(glass.a<0.001)discard_fragment();
    return float4(mix(float3(1),clamp(glass.rgb,0.0,1.0),clamp(glass.a,0.0,1.0)),1);
}

// Standard temporal reconstruction, implemented natively for the shadow mask only.
// Complementary's shadowSampling/taa/composite6 are the reference for the filter structure;
// this is an independent implementation rather than imported shaderpack code.
struct SunTemporalFrame { float4x4 inverseView,previousView;float4 cameraDelta,config; };
struct SunScreenVertex { float4 position [[position]]; };
vertex SunScreenVertex sun_screen_vs(uint vid [[vertex_id]]) {
    float2 xy=float2((vid<<1)&2,vid&2);
    return {float4(xy*2.0-1.0,0,1)};
}
// Lighting G-buffer views (mcsun_gbuffer.inc). View 5 rebuilds the terrain pass's color exactly as
// own_fs forms it before shadows and fog: albedo x lightmap (block light, unoccluded sky row), the
// chunk fade toward the fog color, then the sun-facing diffuse term.
fragment float4 sun_gbuffer_view_fs(SunScreenVertex in [[stage_in]],constant SunFrame &sun [[buffer(1)]],constant uint &view [[buffer(2)]],
    constant Fog &fog [[buffer(24)]],texture2d<float> normals [[texture(0)]],texture2d<float> albedo [[texture(1)]],
    texture2d<float> light [[texture(2)]],texture2d<float> lightmap [[texture(3)]],sampler lightSampler [[sampler(0)]]) {
    uint2 pixel=uint2(in.position.xy);float4 surface=normals.read(pixel);
    if(surface.w<0)discard_fragment();
    float4 base=albedo.read(pixel),data=light.read(pixel);
    float3 normal=surface.xyz*rsqrt(max(dot(surface.xyz,surface.xyz),1e-12));
    if(view==1)return float4(base.rgb,1);
    if(view==2)return float4(data.x,data.y,0,1);
    if(view==3)return float4(normal*.5+.5,1);
    if(view==4){float id=round(data.z*255);return id<.5?float4(.3,.3,.3,1):float4(fract(float3(.37,.61,.83)*id),1);}
    float4 color=base*lightmap.sample(lightSampler,float2(data.x,15.5/16.0),level(0.0));
    color=mix(fog.FogColor*float4(1,1,1,color.a),color,data.w);
    float diffuse=0.80+0.20*max(0.0,dot(normal,sun.sun.xyz));
    color.rgb*=mix(1.0,diffuse,sun.sun.w);
    return color;
}

// Native lighting model over the G-buffer (replaces vanilla's lightmap while the shader is on).
// Linear light with values above 1: direct sun by time of day through the shadow visibility, sky
// light from the G-buffer's sky coordinate, warm block light and a small floor; then a filmic
// curve on luminance, the viewer's saturation and display encoding. Atmospheric fog and chunk fade
// compose in linear scene space before display encoding; HDR tone mapping follows in the camera pass.
// p: SunTuning's values (SunTuning.java order, four per float4). env: x moon brightness, y rain,
// z camera in water/lava/powder snow, w native sky and fog on. moon: 1 while the moon is the
// directional light (sun.sun then points to the moon). hand: [0] light origin (camera-relative) and
// main-hand level, [1] main-hand color and off-hand level, [2] off-hand color (red < 0: block light color).
struct SunLighting {
    uint enabled,srgb;float exposure,saturation,contrast,moon,pad0,pad1;
    float4 env;float4 p[19];float4 hand[3];float4 materials[4];
    // 48-byte aurora extension: axis XZ / curvature Y / height W, tangent XZ / inverse extent Y / amount W,
    // sample count / inverse count / motion speed; w is not the aurora's: the camera's height above sea level (sunHaze).
    float4 aurora[3];
    // 64-byte color grading extension (SunLook): [0] white balance (temperature, tint) / automatic exposure factor,
    // [1] lift / vibrance, [2] gamma / tone curve, [3] gain / white point.
    float4 grade[4];
    // Sun/moon radiance and angular radius, glows/phase/surface, horizon/rain/shapes (sun 0 = vanilla).
    float4 celestial[14]; // [5] centroid/flux/count; [6..13] lit source XY/weight/footprint
    // Sky effects' looks, linear: aurora low color / ribbon width, high color / rays, nebula deep color / size, glow color / speed.
    float4 sky[4];
};
// Lighting passes are specialized for effects that are currently off, so their code leaves the shader entirely instead
// of only being skipped (it would still cost registers and occupancy). Undefined (pipelines that do not specialize):
// on, decided at run time as before. 60: aurora, nebula, stars or rainbow visible; 61: rain wetness; 62: colored block light.
// Copies of this code that are never specialized (SunEntityMsl) replace the block between the markers with plain
// true constants: a function touching a function constant can only be built from a specialized function.
// [lit specialization]
constant bool SUN_SKY_FX_SET [[function_constant(60)]];
constant bool SUN_WET_SET [[function_constant(61)]];
constant bool SUN_COLORED_SET [[function_constant(62)]];
constant bool SUN_SKY_FX=!is_function_constant_defined(SUN_SKY_FX_SET)||SUN_SKY_FX_SET;
constant bool SUN_WET=!is_function_constant_defined(SUN_WET_SET)||SUN_WET_SET;
constant bool SUN_COLORED=!is_function_constant_defined(SUN_COLORED_SET)||SUN_COLORED_SET;
// [/lit specialization]
// Native sparse section RGB field. All offsets are uint words; RGBA8 storage, float arithmetic.
// Hash keys use integer world coordinates.
static uint blockLightSlot(device const uint *d,int3 section) {
    uint mask=d[1],at=(uint(section.x)*73856093u^uint(section.y)*19349663u^uint(section.z)*83492791u)&mask;
    for(uint i=0;i<=mask;i++) {uint p=d[4]+at*4,slot=d[p+3];if(!slot)return 0;
        if(all(as_type<int3>(uint3(d[p],d[p+1],d[p+2]))==section))return slot;at=(at+1)&mask;
    }return 0;
}
static uint blockLightIndex(device const uint *d,uint section,int3 p) {
    if(!section)return 0xffffffffu;
    for(uint axis=0;axis<3;axis++)if(p[axis]<0||p[axis]>15){
        uint side=axis*2+(p[axis]<0?1:0);section=d[d[6]+(section-1)*6+side];if(!section)return 0xffffffffu;p[axis]&=15;
    }
    return (section-1)*4096+uint(p.x+p.z*16+p.y*256);
}
// RGBA8 colour and level transport. Alpha attenuates by 1/15 per edge; each colour takes the maximum incoming
// contribution, preserving a single source's hue. Alpha is shared by different sources, so it does NOT bound the
// colour's path length. Native code instead evaluates exactly 16 steps from zero (source plus at most 15 edges).
// Local updates include a computation halo and retain only their affected output; no cached light can extend this
// finite dependency radius. Stained glass filters RGB, and complete face masks block transport.
kernel void blocklight_reference(device const uint *d [[buffer(0)]],device const uchar4 *previous [[buffer(1)]],device uchar4 *next [[buffer(2)]],uint index [[thread_position_in_grid]]) {
    constexpr int3 offsets[6]={int3(1,0,0),int3(-1,0,0),int3(0,1,0),int3(0,-1,0),int3(0,0,1),int3(0,0,-1)};
    if(index>=(d[10]!=0?d[9]:d[0])*4096)return;uint slot=d[10]!=0?d[d[8]+index/4096]:index/4096+1,at=index%4096;index=(slot-1)*4096+at;uint cell=d[d[2]+index];int3 p=int3(at&15,at>>8,(at>>4)&15);
    bool source=(cell&0xffffffu)&&!(cell&(1u<<25));
    if((cell>>26)==63u&&!source){next[index]=uchar4(0);return;}
    float3 tint=float3(cell&255u,(cell>>8)&255u,(cell>>16)&255u)/255.0,color=source?tint:float3(0);
    // A source's level is its strongest channel: palette colours peak at 1, scaled by emission/15.
    float level=source?max(tint.r,max(tint.g,tint.b)):0.0;
    for(uint face=0;face<6;face++) {
        if(cell&(1u<<(26+face)))continue;
        uint other=blockLightIndex(d,slot,p+offsets[face]);if(other==0xffffffffu)continue;
        uint neighbor=d[d[2]+other];bool emits=(neighbor&0xffffffu)&&!(neighbor&(1u<<25));
        if(!emits&&(neighbor&(1u<<(26+(face^1u)))))continue;
        float4 v=float4(previous[other])*(1.0/255.0);float carried=v.a-1.0/15.0;
        if(carried<=.5/255.0)continue;
        level=max(level,carried);color=max(color,v.rgb*(carried/v.a));
    }
    if(cell&(1u<<25))color*=tint;
    next[index]=uchar4(round(saturate(float4(color,level))*255.0));
}
// Light may cross from cell a into its neighbour b through face `direction` of a: a's face is open, and so is b's
// facing side unless b is itself a source.
static bool blockLightOpen(uint a,uint b,uint direction) {
    bool source=(b&0xffffffu)&&!(b&(1u<<25));
    return !(a&(1u<<(26+direction)))&&(source||!(b&(1u<<(26+(direction^1u)))));
}
// Trilinear over all eight surrounding cell centres, so the field is continuous in open space. Corners that are solid,
// outside the field or not reachable from the sample's own cell through open faces inside the 2x2x2 cube are left out:
// no light through walls. One section lookup; neighbouring sections come from the adjacency table. Each corner's cell
// and value is read once, and reachability uses only those eight cells.
static float4 sunBlockLight(float3 p,float3 n,constant SunFrame &sun,device const uint *d) {
    if(d==nullptr||d[0]==0)return float4(0);
    float3 local=p-sun.camOffset.xyz+n*.55-.5,corner=floor(local),f=local-corner;
    int3 base=int3(corner)+sun.camBlock.xyz;
    uint slot=blockLightSlot(d,base>>4);if(!slot)return float4(0);
    int3 inner=base&15;uint index[8],cells[8],present=0,faces=0;
    // Most cubes lie inside one section: fixed offsets (x +1, y +256, z +16), no boundary steps.
    bool within=all(inner<15);uint first=(slot-1)*4096+uint(inner.x+inner.z*16+inner.y*256);
    // Fully unrolled with constant indices, so the eight corners stay in registers.
    #pragma unroll
    for(uint i=0;i<8;i++) {
        index[i]=within?first+(i&1)+((i>>1)&1)*256+(i>>2)*16:blockLightIndex(d,slot,inner+int3(i&1,(i>>1)&1,i>>2));
        bool inside=index[i]!=0xffffffffu;cells[i]=inside?d[d[2]+index[i]]:63u<<26;present|=inside?1u<<i:0u;faces|=cells[i];
    }
    // The sample's own cell: the nearest corner.
    uint own=uint(f.x>=.5)|(uint(f.y>=.5)<<1)|(uint(f.z>=.5)<<2);
    if(!((present>>own)&1u)||((cells[own]>>26)==63u&&!(cells[own]&0xffffffu)))return float4(0);
    // Open air (all eight present, no face blocks anywhere): every corner is reachable. Otherwise the cube's twelve
    // edges become per-axis bit masks (bit i: edge between corner i and i+axis bit, indexed by its lower corner). A path
    // inside the cube has at most seven steps, so seven register-only passes give the own cell's whole open component.
    uint reach=255u;
    if(present!=255u||(faces>>26)) {
        uint up[3]={0,0,0},down[3]={0,0,0};
        #pragma unroll
        for(uint axis=0;axis<3;axis++) {
            uint bit=1u<<axis;
            #pragma unroll
            for(uint i=0;i<8;i++)if(!(i&bit)) {
                uint j=i|bit;bool both=((present>>i)&1u)&&((present>>j)&1u);
                up[axis]|=both&&blockLightOpen(cells[i],cells[j],axis*2)?1u<<i:0u;
                down[axis]|=both&&blockLightOpen(cells[j],cells[i],axis*2+1)?1u<<i:0u;
            }
        }
        reach=1u<<own;
        #pragma unroll
        for(uint pass=0;pass<7;pass++)
            reach|=((reach&up[0])<<1)|((reach>>1)&down[0])|((reach&up[1])<<2)|((reach>>2)&down[1])|((reach&up[2])<<4)|((reach>>4)&down[2]);
    }
    device const uchar4 *values=(device const uchar4 *)(d+d[3]);float4 result=0;float weight=0;
    #pragma unroll
    for(uint i=0;i<8;i++)if((reach>>i)&1u) {
        if((cells[i]>>26)==63u&&!(cells[i]&0xffffffu))continue;
        float3 w=select(1.0-f,f,bool3(i&1,i&2,i&4));float k=w.x*w.y*w.z;
        result+=float4(values[index[i]])*k;weight+=k;
    }
    return weight>0?result/(weight*255.0):float4(0);
}
// The real sun direction, also while the moon lights the scene.
static float3 sunToward(constant SunFrame &sun,constant SunLighting &l) { return l.moon>0.5?-sun.sun.xyz:sun.sun.xyz; }
static float sunFilmicCurve(float x) { return (x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14); }
// Per-channel display curves (SunLook.CURVES). Each one's input is scaled so middle grey keeps the standard curve's
// brightness: switching curves changes the character of highlights and colours, not the overall exposure.
static float3 sunAces(float3 x) {
    // Stephen Hill's fit of the ACES reference rendering and sRGB output transforms.
    x=float3x3(float3(0.59719,0.07600,0.02840),float3(0.35458,0.90834,0.13383),float3(0.04823,0.01566,0.83777))*(x*2.0);
    x=(x*(x+0.0245786)-0.000090537)/(x*(0.983729*x+0.4329510)+0.238081);
    return float3x3(float3(1.60475,-0.10208,-0.00327),float3(-0.53108,1.10813,-0.07276),float3(-0.07367,-0.00605,1.07602))*x;
}
static float3 sunAgx(float3 x) {
    // Troy Sobotka's AgX base look (minimal fit): inset primaries, a log encoding over 16.5 stops, the sigmoid, outset.
    x=float3x3(float3(0.842479062253094,0.0423282422610123,0.0423756549057051),float3(0.0784335999999992,0.878468636469772,0.0784336),
        float3(0.0792237451477643,0.0791661274605434,0.879142973793104))*(x*1.33);
    x=(clamp(log2(max(x,1e-10)),-12.47393,4.026069)+12.47393)/16.499999;
    float3 x2=x*x,x4=x2*x2;
    x=15.5*x4*x2-40.14*x4*x+31.96*x4-6.868*x2*x+0.4298*x2+0.1191*x-0.00232;
    x=float3x3(float3(1.19687900512017,-0.0528968517574562,-0.0529716355144438),float3(-0.0980208811401368,1.15190312990417,-0.0980434501171241),
        float3(-0.0990297440797205,-0.0989611768448433,1.15107367264116))*x;
    return pow(max(x,0.0),2.2);
}
static float3 sunNeutral(float3 x) {
    // Khronos PBR Neutral: true to base colours up to the highlights, which then compress and whiten.
    x*=1.705;
    float low=min(x.r,min(x.g,x.b)),offset=low<0.08?low-6.25*low*low:0.04;x-=offset;
    float peak=max(x.r,max(x.g,x.b));if(peak<0.76)return x;
    float top=1.0-0.0576/(peak-0.52);x*=top/peak;
    return mix(x,float3(top),1.0-1.0/(0.15*(peak-top)+1.0));
}
static float3 sunCurve(float3 x,uint curve) { return curve==1u?sunAces(x):curve==2u?sunAgx(x):sunNeutral(x); }
// The curve maps luminance and keeps each pixel's color ratios, so hues stay put and midtones keep
// their contrast; a per-channel curve oversaturates mids while flattening sunlit surfaces. Colors
// too bright for the display run smoothly toward white; near-black colors lose a little saturation.
// The other curves (grade[2].w) work per channel. grade[3].w scales every curve so the chosen white point (scene
// luminance, SunLook) reaches display white; at the standard curve's default 7.25 it is 1, where that curve saturated.
static float3 sunToneCurve(float3 color,constant SunLighting &lighting) {
    const float3 weights=float3(0.2126,0.7152,0.0722);
    float luma=dot(color,weights);
    if(luma<=1e-6)return float3(0);
    // Contrast pivots around middle grey in log space.
    float graded=0.18*pow(luma/0.18,lighting.contrast),white=lighting.grade[3].w;
    uint curve=uint(lighting.grade[2].w);
    if(curve!=0u)return saturate(sunCurve(color*(graded/luma),curve)*white);
    float mapped=saturate(sunFilmicCurve(graded)*white);
    float3 result=color*(mapped/luma);
    float peak=max(result.r,max(result.g,result.b));
    float whiten=max(peak>1.0?(peak-1.0)/max(peak-mapped,1e-4):0.0,smoothstep(0.80,1.0,mapped)*0.35);
    result=mix(result,float3(mapped),saturate(whiten));
    return mix(result,float3(mapped),smoothstep(0.06,0.0,mapped)*0.30);
}
// Grading on the displayed image: vibrance lifts muted colours more than saturated ones; lift, gamma and gain act in
// display space per channel (shadows, midtones, highlights). Neutral values skip their work.
static float3 sunGrade(float3 color,constant SunLighting &lighting) {
    const float3 weights=float3(0.2126,0.7152,0.0722);
    float vibrance=lighting.grade[1].w;
    if(vibrance!=0.0) {
        float peak=max(color.r,max(color.g,color.b)),chroma=peak>1e-5?(peak-min(color.r,min(color.g,color.b)))/peak:0.0;
        color=max(mix(float3(dot(color,weights)),color,1.0+vibrance*(1.0-chroma)),0.0);
    }
    float3 lift=lighting.grade[1].xyz,gamma=lighting.grade[2].xyz,gain=lighting.grade[3].xyz;
    if(any(lift!=0.0)||any(gamma!=1.0)||any(gain!=1.0)) {
        float3 display=pow(saturate(color),1.0/2.2);
        display=saturate(gain*(display+lift*(1.0-display)));
        color=pow(display,2.2/gamma);
    }
    return color;
}
// The full display transform without the HDR shortcut: exposure (viewer and automatic), white balance, the tone curve,
// grading and saturation.
static float3 sunToneMapFinal(float3 color,constant SunLighting &lighting) {
    const float3 weights=float3(0.2126,0.7152,0.0722);
    color=max(color*(lighting.exposure*lighting.grade[0].w)*lighting.grade[0].xyz,0.0);
    float3 result=sunGrade(sunToneCurve(color,lighting),lighting);
    return saturate(mix(float3(dot(result,weights)),result,lighting.saturation));
}
static float3 sunToneMap(float3 color,constant SunLighting &lighting) {
    // HDR bloom: preserve radiance through the world; grade/tone map once in the final camera composite.
    if(lighting.materials[3].w>.5)return max(color,0.0);
    return sunToneMapFinal(color,lighting);
}
// Display-space color times intensity, as linear light.
static float3 sunTuned(float4 value) { return pow(value.rgb,2.2)*value.w; }
// Day phases from the sun elevation: day (sun above the horizon), noon (high sun).
static float sunDay(float elevation) { return smoothstep(-0.10,0.10,elevation); }
static float sunNoon(float elevation) { return smoothstep(0.05,0.45,elevation); }
// The color of the cloud layer in rain, for its brightness.
constant float3 SUN_CLOUD_TINT=float3(.77,.81,.88);
static float sunRain(constant SunLighting &lighting) { return lighting.env.y*lighting.p[7].z; }
// Weather (SunWeather, once per frame on the CPU, in spare packet slots): p[7].w the share thunder takes from the sky
// (sun.sun.w and the sky light intensities already carry it), p[18].y the lightning flash. Rain's denser, higher haze is
// already in p[16].xy.
static float sunThunderDarken(constant SunLighting &l) { return l.p[7].w; }
static float sunFlash(constant SunLighting &l) { return l.p[18].y; }
// Dimension tag in SunAurora's formerly unused sky[0].w: 0 overworld, -1 Nether, +1 End.
// sky[1].rgb is the interpolated dimension atmosphere, sky[1].w the view border. No extra per-draw ABI.
static int sunDimension(constant SunLighting &l) { return int(l.sky[0].w); }
static float3 sunDimensionSky(float3 d,constant SunLighting &l) {
    float3 atmosphere=pow(max(l.sky[1].rgb,0.0),2.2);
    if(sunDimension(l)<0)return atmosphere*(.8+.2*saturate(1.0-d.y));
    return atmosphere*(.65+.55*pow(1.0-abs(d.y),2.0));
}
static float3 sunDirectColor(constant SunFrame &sun,constant SunLighting &l) {
    if(sunDimension(l)<0)return float3(0);
    if(sunDimension(l)>0)return float3(1.0,.91,.78);
    return l.moon>.5?sunTuned(l.p[17]):mix(sunTuned(l.p[1]),sunTuned(l.p[0]),sunNoon(sunToward(sun,l).y));
}
// Haze in the world's air, densest at the ground and thinning with height above sea level: the optical depth along the view
// through an exponential density, the same from either end of the ray. aurora[2].w: the camera's height above sea level.
// A share of the density stays at any height, so high ground keeps some aerial depth.
constant float SUN_HAZE_FLOOR=0.25;
// Legacy packets use 0/1 for skyEnabled; current GPU copies carry 2 + camera exposure in that boolean slot.
static float sunFogExposure(constant SunLighting &l) { return l.p[16].w>=2.0?saturate(l.p[16].w-2.0):1.0; }
static float sunHaze(float distance,float height,constant SunLighting &l) {
    // Density belongs to the air, not to the receiving block.
    float falloff=max(l.p[16].y,0.0),from=max(l.aurora[2].w,0.0),to=max(from+height,0.0),rise=falloff*(to-from);
    float along=exp(-falloff*from)*(abs(rise)<1e-3?1.0:(1.0-exp(-rise))/rise);
    float density=max(l.p[16].x,0.0)*(SUN_HAZE_FLOOR+(1.0-SUN_HAZE_FLOOR)*along);
    return (1.0-exp(-density*max(distance,0.0)))*sunFogExposure(l);
}
// Below this, fog, haze and a chunk's fade-in change no display value (8-bit targets): the haze color is not needed.
constant float SUN_HAZE_UNSEEN=1.0/512.0;

// Hand light, as Complementary's held lighting: each hand's item level falls off with the distance
// from a point just below the eyes, (level x 0.47 / (distance / range + 6))^4, no shadows. Linear.
static float3 sunHandLight(float3 p,constant SunLighting &l) {
    if(l.p[8].x<0.5||(l.hand[0].w<=0&&l.hand[1].w<=0))return float3(0);
    float distance=length(p-l.hand[0].xyz)/max(l.p[8].z,0.05)+6.0;
    float3 blockColor=pow(l.p[5].rgb,2.2);
    float3 light=float3(0);
    for(uint i=0;i<2;i++) {
        float level=i==0?l.hand[0].w:l.hand[1].w;if(level<=0)continue;
        float3 tint=i==0?l.hand[1].xyz:l.hand[2].xyz;
        float strength=pow(level*0.47/distance,4.0);
        light+=(tint.x<0?blockColor:pow(tint,2.2))*strength;
    }
    return light*l.p[8].y*l.p[5].w;
}
static float3 sunTerrainLight(float3 albedo,float block,float sky,float3 normal,float3 visible,float3 position,constant SunFrame &sun,constant SunLighting &l,device const uint *colored=nullptr) {
    int dimension=sunDimension(l);
    float elevation=sunToward(sun,l).y,day=dimension==0?sunDay(elevation):0,noon=sunNoon(elevation),rain=sunRain(l),moon=l.env.x;
    // The directional light: the sun by day, the moon (its strength already scaled by phase and rain) at night.
    float3 sunColor=sunDirectColor(sun,l);
    float3 skyColor=mix(sunTuned(l.p[4])*moon,mix(sunTuned(l.p[3]),sunTuned(l.p[2]),noon),day);
    // Under the cloud layer (SunWeather's cover in env.y): direct light loses most of its color (sun.sun.w carries its dimming),
    // the sky light turns the cloud's own color, rainDim takes brightness; a lightning strike flashes it.
    sunColor=mix(sunColor,float3(dot(sunColor,float3(0.2126,0.7152,0.0722))),0.7*l.env.y);
    skyColor=mix(skyColor,SUN_CLOUD_TINT*dot(skyColor,float3(0.2126,0.7152,0.0722))*(1.0-0.25*rain),0.75*l.env.y);
    skyColor+=float3(0.78,0.80,1.0)*sunFlash(l)*0.55;
    // Beyond the shadow range a low sky value still keeps caves and interiors out of direct sun.
    float direct=max(dot(normal,sun.sun.xyz),0.0)*sun.sun.w*(dimension==0?smoothstep(0.05,0.6,sky):1.0);
    float luma=dot(visible,float3(0.2126,0.7152,0.0722));
    // Shadow brightness (p[6].z): sky light in the sun's shadow, in proportion to how much sun the spot misses (outdoors,
    // while the sun or moon lights the scene). Continuous in the shadow amount: the full shadow (luma 0) gets the whole fill,
    // as do faces turned away from the sun; it no longer drops back to 1 there, which made converging shadows darken late.
    float sunOut=saturate(sun.sun.w)*smoothstep(0.05,0.6,sky);
    float3 ambient=skyColor*(sky*sky)*(0.70+0.30*(normal.y*0.5+0.5))*mix(1.0,l.p[6].z,sunOut*(1.0-saturate(luma)));
    if(dimension<0) {
        // The Nether is lit by its biome atmosphere and lava, never by a hidden sun or moon.
        sunColor=float3(0);
        ambient=(pow(max(l.sky[1].rgb,0.0),2.2)*.85+float3(.075,.026,.006))
            *(.78+.22*abs(normal.y+.5)/1.5);
    } else if(dimension>0) {
        // The End's warm white light comes from the black hole; ambient fill reaches places with zero sky light.
        // With the black hole switched off nothing casts light (sun.w 0), so the fill alone keeps the world readable.
        sunColor=float3(1.0,.91,.78);
        ambient=mix(float3(.30,.30,.34),float3(.10,.105,.13),saturate(sun.sun.w*2.0))*(.75+.25*saturate(normal.y*.5+.5));
    }
    // Warm block light with a steep rise near the source, softened outdoors in daylight.
    float3 torch=sunTuned(l.p[5])*(pow(block,3.0)*1.7+block*0.06);
    // Header word 11: where the colored field ends around the camera (0: no limit). The last third fades into the plain
    // block light colour; beyond it the field is not even read.
    float near=SUN_COLORED&&colored!=nullptr&&colored[0]>0?(colored[11]?1.0-smoothstep(float(colored[11])*.65,float(colored[11]),length(position)):1.0):0.0;
    if(near>0&&(block>0||colored[7]!=0)){
        float4 field=sunBlockLight(position,normal,sun,colored);
        float3 rgb=pow(field.rgb,2.2);float energy=dot(rgb,float3(.2126,.7152,.0722));
        // Vanilla's brightness, the field's hue. Where vanilla has no block light the field can only come from the
        // artistic extra emitters (redstone blocks): a faint glow scaled by their level.
        if(energy>1e-10){float luminance=dot(torch,float3(.2126,.7152,.0722));torch=mix(torch,block>0?rgb*(luminance/energy):rgb*(field.a*.025*l.p[5].w),near);}
    }
    // The hand light joins block light in quadrature like Complementary, so a torch next to a held one does not double.
    torch=sqrt(torch*torch+pow(sunHandLight(position,l),2.0))*mix(1.0,1.0-l.p[6].y,day*sky*(1.0-rain));
    return albedo*(sunColor*direct*visible+ambient+torch+float3(0.70,0.85,1.20)*l.p[6].x);
}

static float3 ownWaterBlockLight(float3 p,float3 n,float block,constant SunFrame &sun,constant SunLighting &l,device const uint *colored) {
    if(colored==nullptr||colored[0]==0)return float3(0);
    // Open water far from any light: no block light and no extra emitters leave only the hand's light of the whole lighting model.
    if(sunDimension(l)==0&&block<=0&&colored[7]==0)return sunHandLight(p,l);
    return max(sunTerrainLight(float3(1),block,0,n,float3(0),p,sun,l,colored)-float3(.70,.85,1.20)*l.p[6].x,0.0);
}

// Independent Metal material shading. The existing RGBA8 light buffer carries the CPU profile ID.
constant bool SURFACE_ENABLED_SET [[function_constant(51)]];
constant bool SURFACE_ENABLED=is_function_constant_defined(SURFACE_ENABLED_SET)&&SURFACE_ENABLED_SET;
// Material detail selectors live in the palette's spare half component, with no new G-buffer data or texture reads.
// Reference behaviors: Complementary's iron/gold/copper/gem/stone texture responses; independent Metal equations.
static float3 sunSkyLinear(float3 direction,constant SunFrame &sun,constant SunLighting &l);
// Wet surfaces in rain, after Complementary's rain puddles in their macOS form (a sky reflection; puddles also take the
// world-reflection trace when it is on). p[18].z: wetness times strength (SunWeather.Wetness on the CPU; 0 when dry or off,
// and everything is skipped), p[18].w: puddle coverage. Only under an open sky (full sky light), dry next to block light,
// puddles on upward faces and a thinner film elsewhere. Puddle shapes from procedural value noise over world space (no texture
// fetch), rain ripples animated by the wind clock (camOffset.w), the native sky reflected, darker in shadow. Linear light.
// Evaluated once per pixel (sunWetness) and shared: material surfaces turn it into lower roughness and more gloss in their own
// highlight and sky reflection (sunSurfaceMaterial), all other surfaces get the plain wet sheen (sunWetSheen). Never both.
// Arithmetic hash (Dave Hoskins' hash12): no transcendental per lattice point.
static float sunWetHash(float2 c) { float3 q=fract(float3(c.xyx)*0.1031);q+=dot(q,q.yzx+33.33);return fract((q.x+q.y)*q.z); }
static float sunWetNoise(float2 x) {
    float2 i=floor(x),f=fract(x);f=f*f*(3.0-2.0*f);
    return mix(mix(sunWetHash(i),sunWetHash(i+float2(1,0)),f.x),mix(sunWetHash(i+float2(0,1)),sunWetHash(i+float2(1,1)),f.x),f.y);
}
// amount: how wet (0 dry), puddle: the puddle share, boost: the strength above 1, normal: rippled on puddles (n otherwise).
struct SunWet { float amount,puddle,boost; float3 normal; };
static SunWet sunWetness(float block,float sky,float3 n,float3 p,constant SunFrame &sun,constant SunLighting &l) {
    SunWet r={0,0,1,n};if(!SUN_WET)return r;float wet=l.p[18].z;
    if(wet<=0.0)return r;
    // Complementary's mask: full sky light (open sky above) and no strong block light.
    float exposed=smoothstep(0.92,1.0,sky)*saturate((1.0-1.15*block)*10.0);
    if(exposed<=0.0)return r;
    float up=saturate(n.y*2.0-1.0);up*=up;
    // World position wrapped every 1024 blocks: stable shapes without large-number precision loss.
    float2 w=float2(sun.camBlock.xz&1023)+p.xz-sun.camOffset.xz;
    float soaked=saturate(wet),cover=saturate(l.p[18].w),puddle=0.0;
    // Puddle shapes only where puddles can form (upward faces once the ground is soaked): walls skip the noise.
    if(up>0.0&&soaked>0.35) {
        float shape=sunWetNoise(w*0.05)*0.65+sunWetNoise(w*0.11+17.0)*0.35;
        puddle=up*smoothstep(0.80-0.55*cover,0.86-0.55*cover,shape)*smoothstep(0.35,0.9,soaked);
    }
    float amount=exposed*max(soaked*(0.25+0.75*up)*0.45,puddle);
    if(amount<=SUN_HAZE_UNSEEN)return r;
    // Rain ripples on the puddles: a small, moving tilt of the normal.
    if(puddle>0.0) {
        float t=sun.camOffset.w;
        float2 ripple=float2(sin(w.x*3.1+t*2.3)+sin(w.y*2.7-t*1.9),sin(w.y*3.3+t*2.1)+sin(w.x*2.9+t*1.7))*0.018*puddle;
        r.normal=normalize(float3(n.x+ripple.x,n.y,n.z+ripple.y));
    }
    r.amount=amount;r.puddle=puddle;r.boost=max(1.0,wet);return r;
}
// The water film's roughness: sharp on puddles, broad on the film.
static float sunWetRough(SunWet w) { return mix(0.4,0.1,saturate(w.puddle*1.5)); }
// Complementary's puddle reflection weight, (1 - n.v) / 2.
static float sunWetReflection(SunWet w,float nv) { return saturate((1.0-nv)*0.5*w.amount*w.boost); }
// Wet ground is darker: the diffuse light only, before any highlight is added.
static float3 sunWetDarken(float3 lit,SunWet w) { return w.amount>0.0?lit*(1.0-0.22*w.amount*w.boost):lit; }
// The wet highlight and sky reflection on surfaces without their own material shading.
static float3 sunWetSheen(float3 lit,SunWet w,float sky,float3 visible,float3 p,constant SunFrame &sun,constant SunLighting &l) {
    if(w.amount<=0.0)return lit;
    float3 eyeDelta=float3(l.pad0,l.pad1,l.hand[2].w)-p;float3 v=eyeDelta*rsqrt(max(dot(eyeDelta,eyeDelta),1e-8));float nv=saturate(dot(w.normal,v));
    float luma=dot(visible,float3(0.2126,0.7152,0.0722));float3 result=lit;
    float direct=sun.sun.w*smoothstep(0.05,0.6,sky),nl=saturate(dot(w.normal,sun.sun.xyz));
    if(direct>0.0&&nl>0.0) {
        float3 h=normalize(v+sun.sun.xyz);float nh=saturate(dot(w.normal,h));
        float rough=sunWetRough(w),a=rough*rough,a2=a*a,d=nh*nh*(a2-1.0)+1.0;
        float3 radiance=sunDirectColor(sun,l);
        float3 spec=a2/(3.14159265*d*d)*0.04*nl/max(4.0*nv,0.1)*radiance*visible*direct*w.amount*w.boost*1.5;
        result+=spec/(1.0+spec*0.25);
    }
    // Complementary's low-quality puddle reflection: the sky, darker where the sun is shadowed.
    float weight=sunWetReflection(w,nv);
    if(weight>SUN_HAZE_UNSEEN) {
        float3 ray=reflect(-v,w.normal);ray.y=abs(ray.y);
        result=mix(result,sunSkyLinear(normalize(ray),sun,l)*(0.5+0.5*luma),weight);
    }
    return result;
}
static float3 sunWetSurface(float3 lit,float block,float sky,float3 n,float3 visible,float3 p,constant SunFrame &sun,constant SunLighting &l) {
    SunWet w=sunWetness(block,sky,n,p,sun,l);
    return sunWetSheen(sunWetDarken(lit,w),w,sky,visible,p,sun,l);
}
static float sunSurfaceTextureResponse(float3 texel,uint kind) {
    float3 square=texel*texel,fourth=square*square;
    if(kind==1)return fourth.r;
    if(kind==2)return fourth.g;
    if(kind==3)return saturate(fourth.r+.25*square.g)*mix(.25,1.0,smoothstep(-.1,.25,texel.r-texel.g));
    if(kind==4)return saturate(.25+.75*fourth.g);
    if(kind==5){float y=dot(texel,float3(.2126,.7152,.0722));return y*y*y*y;}
    if(kind==6)return .8*square.b;
    if(kind==7)return saturate(2*fourth.g);
    return saturate(1-abs(texel.r-.3)*3.5)*.65;
}
// wet: this pixel's wetness (sunWetness); wetShaded: set when this shading drew the wet highlight and reflection itself.
static float3 sunSurfaceMaterial(float3 lit,float3 texel,uint id,float sky,float3 n,float3 visible,float3 p,
    constant SunFrame &sun,constant SunLighting &l,SunWet wet,thread bool &wetShaded) {
    wetShaded=false;
    if(!SURFACE_ENABLED||l.materials[0].x<.5||id==0||id>=SURFACE_PROFILE_COUNT)return lit;
    SurfaceProfile m=SURFACE_PROFILES[id];uint group=uint(m.optical.w);
    float enabled=group==1?l.materials[1].y:group==2?l.materials[1].z:group==3?l.materials[1].w:
        group==4?l.materials[2].x:group==5?l.materials[2].y:l.materials[2].z;
    if(enabled<.5)return lit;
    wetShaded=true;
    float3 albedo=pow(max(texel,0.0),2.2);float high=max(texel.r,max(texel.g,texel.b)),low=min(texel.r,min(texel.g,texel.b));
    float chroma=high-low,luma=dot(texel,float3(.2126,.7152,.0722));
    float mask=1,emissive=1,metal=float(m.optical.y),gloss=float(m.optical.z);
    uint mode=uint(m.detail.x);
    if(mode>=3&&mode<=9) {
        // Ore inclusions only; the grey host rock keeps the original diffuse lighting.
        mask=smoothstep(.035,.16,chroma);
        if(mode==3)mask*=smoothstep(.015,.13,texel.r-texel.b);
        if(mode==4)mask*=smoothstep(.06,.25,texel.g-texel.b);
        if(mode==5)mask*=smoothstep(.025,.15,max(texel.r,texel.g)-texel.b);
        if(mode==6)mask*=smoothstep(.025,.15,min(texel.g,texel.b)-texel.r);
        if(mode==7)mask*=smoothstep(.04,.2,texel.g-max(texel.r,texel.b));
        if(mode==8)mask*=smoothstep(.04,.18,texel.b-texel.r);
        if(mode==9)mask*=smoothstep(.04,.2,texel.r-max(texel.g,texel.b));
        emissive=mask;metal*=mask;gloss*=mask;
    } else if(mode==1) {
        // Copper's oxidized green parts have less metallic response.
        float copper=smoothstep(-.15,.2,texel.r-texel.g);metal*=mix(.2,1.0,copper);
    } else if(mode==2) {
        mask=1-smoothstep(.08,.2,abs(texel.r-texel.b));
        float gold=smoothstep(.12,.35,texel.g-texel.b);mask=max(mask,gold);metal*=mask;gloss*=mask;
    }
    if(mode==10)emissive=smoothstep(.06,.25,texel.r-max(texel.g*.8,texel.b))*smoothstep(.25,.7,high);
    if(mode==11)emissive=smoothstep(.3,.75,luma);
    if(mode==12)emissive=smoothstep(.08,.28,texel.r-texel.b)*smoothstep(.45,.85,high);
    if(mode==13)emissive=smoothstep(.12,.38,texel.r-texel.b)*smoothstep(.35,.8,high);
    if(mode==15)emissive=smoothstep(.06,.25,max(texel.g,texel.b)-texel.r)*smoothstep(.2,.65,high);
    if(mode==16)emissive=smoothstep(.2,.75,luma);
    if(mode==17)emissive=smoothstep(.04,.22,min(texel.g,texel.b)-texel.r);
    if(mode==18)emissive=smoothstep(.04,.2,min(texel.r,texel.b)-texel.g);
    float emission=float(m.detail.y);
    if(mode>=3&&mode<=9 && l.materials[2].w>.5)emission=max(emission,.75);
    float3 eyeDelta=float3(l.pad0,l.pad1,l.hand[2].w)-p;float distance2=max(dot(eyeDelta,eyeDelta),1e-8);float3 v=eyeDelta*rsqrt(distance2),light=sun.sun.xyz;
    // The water film's rippled normal for the highlight and reflection (n itself while dry).
    float3 sn=wet.normal;
    float nl=saturate(dot(sn,light)),nv=max(.02,dot(sn,v));
    float direct=sun.sun.w*smoothstep(.05,.6,sky);
    float3 radiance=sunDirectColor(sun,l);
    float3 result=lit*(1-metal*.3);
    float rough=clamp(float(m.optical.x)*l.materials[0].z*(1.12-.24*luma),.16,1.0);
    uint detail=uint(m.detail.w);
    // Off/zero keeps the old profile response, including the existing ore host-rock masks.
    if(detail>0&&l.p[9].x>.5&&l.p[9].y>0) {
        float response=sunSurfaceTextureResponse(saturate(texel),detail),strength=clamp(l.p[9].y,0.0,2.0);
        rough=clamp(rough*max(.15,1+(mix(1.55,.65,response)-1)*strength),.16,1.0);
        gloss*=max(0.0,1+(mix(.2,1.8,response)-1)*strength);
    }
    // Wetness smooths the surface and adds its film's gloss to the one highlight, instead of a second highlight on top.
    float specRough=wet.amount>0?mix(rough,min(rough,sunWetRough(wet)),saturate(wet.amount*2)):rough;
    float specular=max(gloss*l.materials[0].y,1.5*wet.amount*wet.boost);
    if(specular>0&&nl>0&&direct>0) {
        float3 h=v+light;h*=rsqrt(max(dot(h,h),1e-8));
        float nh=saturate(dot(sn,h)),vh=saturate(dot(v,h));
        float a=specRough*specRough,a2=a*a,denom=nh*nh*(a2-1)+1;
        float d=a2/(3.14159265*denom*denom);
        float k=(specRough+1)*(specRough+1)*.125;
        float g=nv/(nv*(1-k)+k)*nl/(nl*(1-k)+k);
        float3 tint=texel/max(high,.001);tint=mix(float3(1),tint,metal);
        float3 f0=mix(float3(.04),tint*.65,metal);float f=1-vh;float f2=f*f;
        float3 fresnel=f0+(1-f0)*(f2*f2*f);
        float3 spec=d*g*fresnel/max(4*nv,.08)*radiance*visible*direct*specular;
        // Finite-area highlights stay bounded at grazing angles and the minimum roughness.
        result+=spec/(1+spec*.25);
    }
    // Analytic sky fallback on smooth opaque materials. Skip before sky evaluation in caves, on rough/diffuse surfaces or Off.
    // The same Fresnel, sky exposure and roughness roles as the reference; no screen-space/voxel ray search or extra target.
    // The wet film's sky reflection shares this one sky evaluation (Complementary's puddle mix after the material's own).
    float weight=0,wetWeight=sunWetReflection(wet,saturate(dot(sn,v)));
    if(detail>0&&l.p[9].z>.5&&l.p[9].w>0&&gloss>0&&l.env.w>.5&&l.p[16].w>.5) {
        float exposure=smoothstep(.45,.95,sky),smooth=1-rough;
        weight=exposure*smooth*smooth*gloss*l.p[9].w;
    }
    if(weight>SUN_HAZE_UNSEEN||wetWeight>SUN_HAZE_UNSEEN) {
        float3 ray=reflect(-v,sn);ray=normalize(mix(ray,sn,specRough*specRough*.65));
        if(wet.amount>0)ray.y=abs(ray.y);
        float3 reflected=sunSkyLinear(normalize(ray),sun,l);
        if(weight>SUN_HAZE_UNSEEN) {
            float f=1-saturate(dot(sn,v)),f2=f*f;
            float3 tint=mix(float3(1),texel/max(high,.001),metal);
            float3 f0=mix(float3(.04),tint*.65,metal),fresnel=f0+(1-f0)*(f2*f2*f);
            result+=reflected*fresnel*min(weight,2.0);
        }
        if(wetWeight>SUN_HAZE_UNSEEN)result=mix(result,reflected*(.5+.5*dot(visible,float3(.2126,.7152,.0722))),wetWeight);
    }
    if(m.detail.z>0&&l.materials[1].x>0) {
        float through=pow(saturate(dot(-light,v)),4.0)*(.3+.7*(1-abs(dot(n,light))));
        result+=albedo*radiance*visible*direct*through*float(m.detail.z)*l.materials[1].x;
    }
    result+=albedo*emission*emissive*l.materials[0].w;
    return max(result,0.0);
}
static float3 sunSurfaceMaterial(float3 lit,float3 texel,uint id,float sky,float3 n,float3 visible,float3 p,
    constant SunFrame &sun,constant SunLighting &l) {
    SunWet dry={0,0,1,n};bool wetShaded;
    return sunSurfaceMaterial(lit,texel,id,sky,n,visible,p,sun,l,dry,wetShaded);
}

// Native sky (SunTuning group SKY): zenith-to-horizon gradient for day and night, a sunset band
// on the sun's side near the horizon, darker ground below it, and glare around sun and moon. Linear.
// glareDirection: where the viewer really looks. Fog flattens direction to keep its colour at the horizon's; the glare
// must not follow, or every downward view toward the sun gets its full glow: a wedge reaching through the ground.
// glare: how much there is of what belongs to the sun's direction, its glow and the sunset band. Thin fog in front of a hill or
// over the ground must not show the sun the hill hides, nor take on the sunset's colour in a fan toward it.
static float3 sunSkyLinear(float3 direction,float3 glareDirection,float glare,constant SunFrame &sun,constant SunLighting &l) {
    if(sunDimension(l)!=0)return sunDimensionSky(direction,l);
    float3 toward=sunToward(sun,l);float up=direction.y,facing=dot(direction,toward);
    float day=sunDay(toward.y),noon=sunNoon(toward.y),rain=sunRain(l),moon=l.env.x;
    // Integer and half powers as products: the same curves without per-pixel log2/exp2.
    float below=1.0-saturate(up),horizon=below*below*sqrt(below);
    float3 color=mix(mix(sunTuned(l.p[12]),sunTuned(l.p[13]),horizon)*moon,mix(sunTuned(l.p[10]),sunTuned(l.p[11]),horizon),day);
    float edge=1.0-abs(up),edge2=edge*edge,side=saturate(facing*0.5+0.5);
    float band=edge2*edge2*edge*(0.35+0.65*side*side*side)*(1.0-noon)*day*glare;
    color=mix(color,sunTuned(l.p[14]),saturate(band));
    color*=mix(1.0,0.35,smoothstep(0.0,0.4,-up));
    float3 moonDirection=-toward;float moonEmission=1.0;
    if(l.celestial[3].z>.5&&l.env.w>.5) {
        float3 right=normalize(cross(float3(0,0,1),moonDirection)),up=cross(moonDirection,right);
        moonDirection=normalize(moonDirection+tan(l.celestial[1].w)*(right*l.celestial[5].x+up*l.celestial[5].y));
        moonEmission=l.celestial[5].z*l.celestial[4].w;
    }
    float sunward=saturate(dot(glareDirection,toward)),moonward=saturate(dot(glareDirection,moonDirection));
    float3 glow=mix(sunTuned(float4(l.p[14].rgb,1)),sunTuned(float4(l.p[0].rgb,1)),noon);
    float s2=sunward*sunward,s4=s2*s2,s8=s4*s4,s16=s8*s8,s32=s16*s16,s64=s32*s32,s128=s64*s64;
    float m2=moonward*moonward,m4=m2*m2,m8=m4*m4,m16=m8*m8;
    color+=glow*(s8*s4*0.25+s128*s64*s8*1.5)*l.p[15].x*day*(1.0-.85*l.env.y)*glare;
    color+=float3(0.30,0.38,0.62)*(m16*m8)*0.15*l.p[15].y*(1.0-day)*moon*moonEmission*glare;
    // Under the cloud layer the sky is the cloud: the sky's brightness in the cloud's color, and, as on any overcast day,
    // brightest overhead and darker toward the horizon (the standard overcast sky's rule, softened). rainDim takes brightness.
    float overhead=saturate(up);
    float cloud=dot(color,float3(0.2126,0.7152,0.0722))*(.66+.42*overhead)*(1.0-.25*rain);
    color=mix(color,SUN_CLOUD_TINT*cloud,.92*l.env.y);
    return color*(1.0-sunThunderDarken(l))+float3(.62,.63,.78)*sunFlash(l)*(.7+.3*overhead);
}
static float3 sunSkyLinear(float3 direction,constant SunFrame &sun,constant SunLighting &l) { return sunSkyLinear(direction,direction,1.0,sun,l); }
static float3 sunDisplay(float3 radiance,constant SunLighting &lighting) { return pow(sunToneMap(radiance,lighting),1.0/2.2); }
// Vanilla fog stays where it carries meaning: fluids, blindness-like short fog, caves (low sky light).
static bool sunOwnFog(constant SunLighting &lighting,constant Fog &fog) {
    return lighting.env.w>0.5&&lighting.env.z<0.5&&fog.FogEnvironmentalEnd>48.0;
}
// Night and after-rain sky (SunAtmosphere, once per frame on the CPU; each 0 skips it), in packet values only the CPU reads
// otherwise: p[6].w aurora (its sign the style), p[8].w aurora depth and softness, p[7].x night nebula (> 0, at night) or minus the
// rainbow's width (by day, when there is no nebula), p[7].y rainbow, p[18].x the sky clock (game seconds). Built for Apple GPUs:
// everything reads one baked noise texture through the hardware filter, colour math runs in half, and the soft effects run in
// the half-resolution pass. Sky pixels only; each effect skipped on a uniform branch while invisible. Linear light.
// Inigo Quilez's cheap lattice hash: about half the operations of a hash12, no transcendental.
static float sunSkyHash(float2 c) { c=50.0*fract(c*0.3183099+float2(0.71,0.113));return fract(c.x*c.y*(c.x+c.y)); }
static float sunSkyNoise(float2 x) {
    float2 i=floor(x),f=fract(x);f=f*f*(3.0-2.0*f);
    return mix(mix(sunSkyHash(i),sunSkyHash(i+float2(1,0)),f.x),mix(sunSkyHash(i+float2(0,1)),sunSkyHash(i+float2(1,1)),f.x),f.y);
}
// Independent low-cost counterparts to Complementary's Nether storm and End beams/stars.
// World-space continuous noise: no screen-pixel jitter, no hidden sun, no Overworld night/weather effects.
static float sunDimensionHaze(float3 delta,constant SunLighting &l) {
    float distance=length(delta),range=max(l.sky[1].w,32.0);
    float border=saturate(max(length(delta.xz),abs(delta.y))/range);
    if(sunDimension(l)>0) {
        // The render border only: the End's fog itself runs along the view ray (sunEndFog).
        return 1.0-exp(-3.0*border*border*border*border);
    }
    // A denser low layer over the lava sea, fading with altitude.
    float altitude=l.aurora[2].w+delta.y*.5;
    float low=exp(-max(altitude-32.0,0.0)*.025);
    float storm=sunSkyNoise((delta.xz+float2(l.aurora[0].x,l.aurora[0].z))*.012+float2(l.p[18].x*.008,altitude*.02));
    return 1.0-exp(-distance*(.0025+.006*low*(.65+.35*storm))-2.0*border*border);
}
// End sky: stars, a black hole that is the visible source of the directional light, and a ringed planet.
// Both bodies are menu switches (SunDimension.write): sky[2].x black hole, sky[2].y planet. Sky pixels only; a body that
// is off costs one uniform branch, and every test below rejects before its expensive part.
// Rings, bands and disc radii vary along one axis: two lattice points instead of the 2D noise's four.
static float sunEndNoise(float x) {
    float i=floor(x),f=x-i;f=f*f*(3.0-2.0*f);
    return mix(sunSkyHash(float2(i,i*.37)),sunSkyHash(float2(i+1.0,(i+1.0)*.37)),f);
}
// A star is at least about a pixel wide with its energy kept: thinner ones shimmer as hard dots when the view turns.
static float3 sunEndStars(float3 d,float pixel) {
    float3 sum=float3(0);
    for(int layer=0;layer<2;layer++) {
        float scale=layer==0?110.0:220.0,rarity=layer==0?.9935:.976;
        float3 q=d*scale,cell=floor(q);
        float hash=sunSkyHash(cell.xz+cell.y*float2(17,43)+float(layer)*float2(5.3,9.1));
        if(hash<rarity)continue;
        float pick=(hash-rarity)/(1.0-rarity);
        float3 f=q-cell-.5-(float3(fract(pick*37.7),fract(pick*91.3),fract(pick*53.9))-.5)*.3;
        float own=layer==0?.11:.085,width=max(own,pixel*scale*.72),span=saturate(1.0-dot(f,f)/.16);
        float star=exp(-dot(f,f)/(2.0*width*width))*(own*own)/(width*width)*span*span;
        sum+=mix(float3(1,.82,.66),float3(.72,.84,1),fract(pick*17.3))*star*(.35+2.2*pick*pick);
    }
    return sum;
}
// The same stars in the Overworld's clear night: they turn with the sun, as the vanilla sky does, and sink into the horizon's
// air. sky[1].w: how many of them show (SunAtmosphere: 0 by day and in rain, and the whole branch off then).
static float3 sunNightStars(float3 d,float pixel,constant SunFrame &sun,constant SunLighting &l) {
    float3 toward=sunToward(sun,l);
    float3 turned=float3(toward.y*d.x-toward.x*d.y,toward.x*d.x+toward.y*d.y,d.z);
    return sunEndStars(turned,pixel)*l.sky[1].w*smoothstep(0.0,.14,d.y);
}
// Ring density by distance from the planet's center, in planet radii: Saturn's order of a faint inner ring, a dense bright
// ring, a wide dark division and an outer ring with one narrow gap, under fine grooves. An octave finer than the pixel is not
// sampled and counts as its mean: no moire in the grazing view toward the ring's horizon, and far rings cost the least.
static float sunEndRings(float rho,float foot,thread float &grain) {
    grain=.5;
    if(rho<1.22||rho>5.6)return 0;
    float sum=0,total=0,frequency=7.0,weight=.5;
    for(int i=0;i<4;i++) {
        float detail=saturate(1.2-foot*frequency*1.4);
        sum+=weight*(detail>0?mix(.5,sunEndNoise(rho*frequency+frequency*7.1),detail):.5);
        total+=weight;frequency*=3.1;weight*=.92;
    }
    grain=sum/total;
    // Zone edges and narrow gaps widen with the footprint, or the far rings break into dashes.
    float soft=max(foot,.012),sharp=saturate(1.0-foot*8.0);
    float zones=.20+.78*smoothstep(1.92-soft,1.98+soft,rho);
    zones*=1.0-.95*(smoothstep(3.22-soft,3.28+soft,rho)-smoothstep(3.47-soft,3.53+soft,rho))*saturate(1.0-foot*2.0);
    zones*=1.0-.26*smoothstep(3.48,3.56,rho);
    float narrow=(rho-4.45)/.035;
    zones*=1.0-.85*sharp*exp(-narrow*narrow);
    return saturate(zones*(.42+1.16*grain))*smoothstep(1.22,1.34,rho)*(1.0-smoothstep(4.7,5.6,rho));
}
static float sunEndRings(float rho,float foot) { float grain;return sunEndRings(rho,foot,grain); }
constant float3 SUN_END_RING_NORMAL=float3(-.2775,.9251,.2590);
constant float3 SUN_END_PLANET_CENTER=float3(.7726,.6114,-1.6263);
// Premultiplied radiance throughout: coverage/transparency is applied exactly once.
struct SunEndLayer { float3 radiance;float alpha; };
// Analytic ray tests from a fixed viewpoint just above the ring plane: the planet is too far away for walking to move it.
static SunEndLayer sunEndPlanet(float3 d,float pixel,float3 light,float transparency) {
    float opacity=1.0-saturate(transparency);
    const float3 N=SUN_END_RING_NORMAL,C=SUN_END_PLANET_CENTER;
    SunEndLayer out={float3(0),0};
    float along=dot(C,d),miss=sqrt(max(dot(C,C)-along*along,0.0)),surface=-1.0;
    float reach=max(pixel*along*1.5,.007),coverage=along>0?1.0-smoothstep(1.0-reach,1.0+reach,miss):0.0;
    if(coverage>0) {
        float cap=sqrt(max(1.0-miss*miss,0.0));
        surface=along-cap;
        float3 p=d*surface,n=p-C;
        // Artistic fill: most of the visible face is lit, with the terminator on the side away from the black hole.
        float lit=smoothstep(-.14,.66,dot(n,normalize(light*.62-C*.40)));
        // Latitude bands on three scales, bent by a slow storm field, from rust through gold to cream; greyer poles.
        float latitude=dot(n,N),warp=sunSkyNoise(float2(n.x*3.1+n.z*2.3,latitude*5.0))-.5;
        float bands=sunEndNoise(latitude*7.0+warp*.35+3.3)*.50+sunEndNoise(latitude*19.0+warp*.6+41.0)*.33+sunEndNoise(latitude*58.0+warp*1.1+91.0)*.17;
        float3 color=mix(float3(.44,.30,.17),float3(.80,.62,.36),smoothstep(.22,.50,bands));
        color=mix(color,float3(.98,.89,.70),smoothstep(.48,.76,bands));
        color=mix(color,float3(.50,.49,.50),smoothstep(.72,1.0,abs(latitude))*.6);
        // A warm terminator, then the rings' shadow across the lit surface; a coarse density is enough for it.
        color*=mix(float3(1.0,.70,.46),float3(1.0),smoothstep(.0,.5,lit));
        float toward=dot(light,N),height=dot(p-C,N),ringShadow=0;
        if(toward*height<0&&lit>0)ringShadow=sunEndRings(length(p-light*(height/toward)-C),.03)*.85*opacity;
        // A dimming limb under a thin atmosphere that catches the black hole's light on the side facing it.
        float rim=1.0-cap,rim3=rim*rim*rim,facing=saturate(dot(n,light)*.5+.5);
        out.radiance=(color*(.006+.56*lit*(1.0-ringShadow))*(.32+.68*sqrt(cap))
            +(float3(1.0,.80,.54)*facing*facing*.60+float3(.30,.42,.62)*.05)*rim3)*coverage;
        out.alpha=coverage;
    }
    // Under the plane only: the viewpoint sits above it.
    float descent=dot(d,N);
    if(opacity>0&&descent<-1e-5&&(coverage<1.0||dot(C,N)/descent<surface)) {
        float distance=dot(C,N)/descent;
        float3 hit=d*distance;float rho=length(hit-C);
        float grain;
        float density=sunEndRings(rho,pixel*distance/max(-descent,1e-3),grain);
        if(density>0) {
            // In the planet's shadow only a little scattered light remains.
            float3 offset=C-hit;float forward=dot(offset,light),shade=1.0;
            if(forward>0)shade=mix(.03,1.0,smoothstep(.96,1.04,sqrt(max(dot(offset,offset)-forward*forward,0.0))));
            // Ice from tan to cream by its grooves, and backlit toward the black hole, warm and brighter.
            float back=saturate(dot(d,light)),back2=back*back,back6=back2*back2*back2;
            float3 ring=mix(float3(.58,.47,.33),float3(1.0,.92,.75),smoothstep(.30,.72,grain))*(.05+.50*density)*shade;
            ring*=1.0+float3(1.9,1.35,.85)*back6;
            bool front=surface<0||distance<surface;
            float over=density*opacity*smoothstep(.012,.075,-descent);
            if(front) {
                out.radiance=ring*over+out.radiance*(1.0-over);
                out.alpha=over+out.alpha*(1.0-over);
            } else {
                float visible=over*(1.0-out.alpha);
                out.radiance+=ring*visible;out.alpha+=visible;
            }
        }
    }
    return out;
}
static float3 sunEndLight(constant SunFrame &sun,constant SunLighting &l) {
    float3 light=sunToward(sun,l);float length2=dot(light,light);
    return length2>1e-6?light/sqrt(length2):float3(.9135,.4067,0);
}
static float3 sunEndDiscAxis(float3 light) {
    float3 up=normalize(float3(0,1,0)-light*light.y);
    return normalize(up*.985+cross(light,up)*.10-light*.14);
}
// The air terrain fades into at the render border: a broad glow with no shape of its own. Anything shaped by a body (a limb,
// a band along the rings, the disc's streak) would draw that body's outline on whatever stands in front of it.
static float3 sunEndAir(float3 d,float3 light,constant SunLighting &l) {
    float3 air=sunDimensionSky(d,l);
    float toward=dot(d,light);
    if(l.sky[2].x>.5)air+=float3(1.0,.82,.60)*(.006*exp((toward-1.0)*4.0)+.0008*exp(toward-1.0))*l.p[14].x;
    if(l.sky[2].y>.5) {
        float plane=dot(d,SUN_END_RING_NORMAL)+.012;
        float lit=l.sky[2].x>.5?.30+.70*smoothstep(-.3,1.0,toward):.35;
        air+=float3(.92,.84,.70)*lit*.0065*exp(-plane*plane*40.0)*(1.0-saturate(l.sky[2].z));
    }
    return air;
}
// Light scattered in empty space, the soft air between the bodies, for sky pixels only. Smooth functions of the direction,
// no noise and no lookups.
static float3 sunEndHaze(float3 d,float3 light,constant SunLighting &l) {
    float3 haze=float3(0);float toward=dot(d,light);
    if(l.sky[2].x>.5) {
        float3 axis=sunEndDiscAxis(light);
        float angle=sqrt(max(2.0-2.0*toward,0.0)),across=dot(d-light,axis);
        float size=max(l.aurora[0].w,.05);
        haze=float3(1.0,.82,.60)*(.050*exp(-angle/(.055*size))+.006*exp(-angle/(.26*size))+.0008*exp(-angle/1.0))*l.p[14].x;
        // The disc's light spread along its plane, centered on the hole.
        haze+=float3(1.0,.50,.20)*.045*exp(-across*across/(.0004*size*size))*exp(-angle/(.38*size))*l.p[14].x;
    }
    if(l.sky[2].y>.5) {
        // Centered on the rings' far edge, a little below the plane itself.
        float plane=abs(dot(d,SUN_END_RING_NORMAL)+.012);
        float lit=l.sky[2].x>.5?.30+.70*smoothstep(-.3,1.0,toward):.35;
        haze+=float3(.92,.84,.70)*lit*(.040*exp(-plane*26.0)+.0065*exp(-plane*6.0))*(1.0-saturate(l.sky[2].z));
        // The planet's atmosphere, beyond its limb.
        const float3 C=SUN_END_PLANET_CENTER;
        float along=dot(C,d),miss=sqrt(max(dot(C,C)-along*along,0.0));
        if(along>0&&miss>1.0)haze+=float3(.80,.78,.72)*.045*exp(-(miss-1.0)*9.0);
    }
    return haze;
}
// The planet and its rings stay where they are; only the stars behind the hole are lensed.
// solid: how much of a pixel is a body, which the fog mostly passes in front of (sunEndSky).
static float3 sunEndBackdrop(float3 d,float3 starDirection,float pixel,float3 light,constant SunLighting &l,thread float &solid) {
    float3 color=sunDimensionSky(d,l)+sunEndStars(starDirection,pixel)*l.p[14].y;
    solid=0;
    if(l.sky[2].y<.5)return color;
    SunEndLayer planet=sunEndPlanet(d,pixel,light,l.sky[2].z);
    solid=planet.alpha;
    return color*(1.0-planet.alpha)+planet.radiance;
}
static float3 sunEndBackdrop(float3 d,float3 starDirection,float pixel,float3 light,constant SunLighting &l) { float solid;return sunEndBackdrop(d,starDirection,pixel,light,l,solid); }
// Black hole, Schwarzschild radius 1. The distance sets its apparent size: the shadow's angular radius is 2.6/distance.
// aurora[0].w: the size setting; p[14].x: the disc's brightness (the sunset's slot, unread in the End).
constant float SUN_END_HOLE_CRITICAL=2.598,SUN_END_HOLE_TRACED=11.0,SUN_END_HOLE_START=13.0;
constant float SUN_END_DISC_INNER=2.05,SUN_END_DISC_OUTER=10.0;
// Thin emissive disc at a crossing: blue-white inside through gold to ember outside, spiral streaks sheared by the orbit,
// the approaching side beamed brighter and hotter than the receding one.
static float3 sunEndDisc(float3 p,float rho,float3 ray,float3 axis,float3 tangent,float time,thread float &opacity) {
    float cosine,sine=sincos(time*.55/(rho*sqrt(rho))+rho*1.35,cosine);
    float2 around=float2(dot(p,tangent),dot(p,cross(axis,tangent)))/rho;
    around=float2(around.x*cosine-around.y*sine,around.x*sine+around.y*cosine);
    // Sums of bent sines have no lattice: value noise's flat spots read as contour lines at this contrast.
    float radial=.5+.11*sin(rho*6.7+1.3*sin(rho*1.9))+.11*sin(rho*15.1+2.1*sin(rho*3.3))+.08*sin(rho*37.0+1.7*sin(rho*5.3));
    float swirl=sunSkyNoise(around*4.0+float2(rho*.45,0))*.60+sunSkyNoise(around*13.0+float2(rho*1.4,0))*.40;
    float streaks=.34+1.32*radial*(.18+1.64*swirl);
    float edge=smoothstep(SUN_END_DISC_INNER-.15,SUN_END_DISC_INNER+.35,rho)*(1.0-smoothstep(SUN_END_DISC_OUTER-5.0,SUN_END_DISC_OUTER,rho));
    float beta=sqrt(.5/rho);
    float shift=sqrt(max(1.0-beta*beta,.05))/(1.0+beta*dot(normalize(cross(axis,p)),ray));
    float heat=SUN_END_DISC_INNER/rho,glow=heat*shift;
    float3 color=mix(float3(1.0,.24,.04),float3(1.0,.58,.22),smoothstep(.20,.44,glow));
    color=mix(color,float3(1.0,.88,.68),smoothstep(.44,.78,glow));
    color=mix(color,float3(.84,.91,1.0),smoothstep(.85,1.30,glow));
    opacity=saturate(edge*(.35+.65*heat)*streaks*1.5);
    return color*heat*heat*sqrt(heat)*min(shift*shift*shift*sqrt(shift),7.0)*streaks*edge*2.8;
}
static float3 sunEndHole(float3 d,float pixel,float3 light,float time,constant SunLighting &l,thread float &solid) {
    float cosine=dot(d,light),sine=sqrt(saturate(1.0-cosine*cosine));
    // The chord stands in for the angle: nearly equal where the glow is visible, and no inverse trigonometry per pixel.
    float angle=sqrt(max(2.0-2.0*cosine,0.0));
    const float holeDistance=32.0/max(l.aurora[0].w,.05);
    float impact=holeDistance*sine;
    if(cosine<0||impact>SUN_END_HOLE_TRACED) {
        if(angle>1.1)return sunEndBackdrop(d,d,pixel,light,l,solid)+sunEndHaze(d,light,l);
        // The weak field outside the traced window: stars still bend toward the hole, and its pull fades over the sky.
        float bend=2.0/max(impact,1.0)*(1.0-smoothstep(SUN_END_HOLE_TRACED/holeDistance,1.1,angle));
        float3 lensed=normalize(d+(light-d*cosine)/max(sine,1e-4)*bend);
        return sunEndBackdrop(d,lensed,pixel,light,l,solid)+sunEndHaze(d,light,l);
    }
    // Null geodesic through the disc's neighborhood only: it starts on a sphere around the hole, where the straight ray
    // is still accurate, and stops once it leaves outward and can no longer meet the disc. Drift-kick-drift, step by radius.
    float h2=impact*impact;
    float3 r=(d*(holeDistance*cosine-sqrt(max(SUN_END_HOLE_START*SUN_END_HOLE_START-h2,0.0))))-light*holeDistance,v=d;
    float3 axis=sunEndDiscAxis(light),tangent=normalize(cross(axis,light));
    float3 emitted=float3(0);float clear=1.0,previous=dot(r,axis);
    for(int i=0;i<30;i++) {
        float radius=length(r),dt=radius*mix(.14,.42,saturate((radius-3.0)/8.0));
        float3 start=r;
        r+=v*(dt*.5);
        float r2=dot(r,r);
        v-=r*(1.5*h2*dt/(r2*r2*sqrt(r2)));
        r+=v*(dt*.5);
        float height=dot(r,axis);
        if((height<0)!=(previous<0)) {
            float3 p=mix(start,r,previous/(previous-height));
            float rho=length(p);
            if(rho>SUN_END_DISC_INNER-.15&&rho<SUN_END_DISC_OUTER) {
                float opacity=0;
                float3 disc=sunEndDisc(p,rho,normalize(v),axis,tangent,time,opacity);
                emitted+=clear*disc;
                clear*=1.0-opacity;
            }
        }
        previous=height;
        if(r2<1.0||clear<.02)break;
        if(radius>SUN_END_DISC_OUTER+.5&&dot(r,v)>0)break;
    }
    // The shadow's edge is analytic: exact, antialiased by the pixel's footprint, free of step-count noise.
    float reach=max(pixel*holeDistance,.05);
    float visible=smoothstep(SUN_END_HOLE_CRITICAL-reach,SUN_END_HOLE_CRITICAL+reach,impact);
    float above=max(impact-SUN_END_HOLE_CRITICAL,0.0);
    // A thin bright photon ring, and warm light between it and the disc's first image.
    float3 ring=(float3(1.0,.93,.82)*3.0*exp(-above*30.0)+float3(1.0,.50,.20)*.26*exp(-above*2.2))*visible;
    float3 glow=sunEndHaze(d,light,l)*visible;
    float keep=clear*visible,behind=0;
    float3 scene=keep>.002?sunEndBackdrop(d,normalize(v),pixel,light,l,behind)*keep:float3(0);
    solid=1.0-keep*(1.0-behind);
    return (emitted+ring)*l.p[14].x+glow+scene;
}
// The End's fog, after Complementary's ender beams: a cloudy layer around island height whose thickness follows the clouds
// and grows with distance, integrated front to back along the view ray. An independent, far cheaper form: three samples and
// no dither, because the field is much wider than a step, and a ray outside the layer samples no noise at all.
// sky[2].w: strength (0 when off), sky[3].x: the distance over which it builds up, aurora[0].xz and [2].w: the camera.
// Its look (SunDimension.fogLook), in slots nothing else reads in the End: sky[0].rgb color, sky[3].yzw ember color,
// aurora[1] height, thickness, size, wisps, aurora[2].x speed, .y brightness, .z ember, aurora[0].y black hole light.
constant float SUN_END_FOG_REACH=1100.0;
struct SunEndAir { float3 scatter;float transmittance; };
static SunEndAir sunEndFog(float3 d,float reach,float3 light,constant SunLighting &l) {
    SunEndAir air={float3(0),1.0};
    float strength=clamp(l.sky[2].w,0.0,2.0);
    if(strength<=0||reach<4.0)return air;
    float build=max(l.sky[3].x,32.0),wind=l.p[18].x*.0005*l.aurora[2].x,flat=length(d.xz),before=0,depth=0;
    // Lit by the black hole: warm toward it, the layer's own violet elsewhere.
    float forward=l.sky[2].x>.5?exp((dot(d,light)-1.0)*5.0):0.0;
    float bright=l.aurora[2].y,thickness=max(l.aurora[1].y,.05),wisps=l.aurora[1].w;
    float3 base=mix(l.sky[0].rgb,float3(.46,.27,.14),saturate(forward*.42*l.aurora[0].y));
    for(int i=0;i<3;i++) {
        // Steps lengthen with distance: reach times (i+1)/3 to the power 1.5.
        float next=reach*(i==0?.19245:i==1?.54433:1.0),step=next-before,middle=(before+next)*.5;
        before=next;
        // Everything below is skipped where the fog has not begun or the ray has left the layer.
        float buildUp=smoothstep(build*.12,build,middle);
        if(buildUp<=0)continue;
        float across=middle*flat,grown=1.0+across*.0012,rise=l.aurora[2].w+d.y*middle-l.aurora[1].x,height=abs(rise);
        if(height>=114.0*grown*thickness)continue;
        // The pattern slides with height, so clouds form instead of vertical curtains, and widens with distance, so the
        // long far steps never undersample it. Fine detail exists only where the steps are short.
        float2 q=(float2(l.aurora[0].x,l.aurora[0].z)+d.xz*middle+float2(rise*1.1,-rise*.8))/(grown*max(l.aurora[1].z,.05));
        float cloud=sunSkyNoise(q*.0062+float2(wind,0));
        // Fine wisps from bent sines: no lattice and no hashes, and only where the steps resolve them.
        if(i<2&&wisps>0)cloud=mix(cloud,.5+.25*sin(q.x*.061+1.3*sin(q.y*.037+wind))+.25*sin(q.y*.053-wind+1.1*sin(q.x*.029)),wisps*.6);
        float layer=saturate(1.0-height/((24.0+90.0*cloud*cloud)*grown*thickness));
        float density=layer*layer*smoothstep(.18,.82,cloud)*buildUp;
        if(density<=0)continue;
        float optical=density*step*strength*.0016,amount=optical/(1.0+optical);
        float ember=smoothstep(.42,.92,cloud)*smoothstep(64.0,420.0,across);
        air.scatter+=(1.0-depth)*amount*bright*(base+float3(l.sky[3].y,l.sky[3].z,l.sky[3].w)*ember*l.aurora[2].z);
        depth+=(1.0-depth)*amount;
    }
    air.transmittance=1.0-depth;
    return air;
}
static float3 sunEndSky(float3 d,float pixel,constant SunFrame &sun,constant SunLighting &l) {
    float3 light=sunEndLight(sun,l);
    float solid=0;
    float3 color=l.sky[2].x<.5?sunEndBackdrop(d,d,pixel,light,l,solid)+sunEndHaze(d,light,l):sunEndHole(d,pixel,light,l.p[18].x,l,solid);
    SunEndAir air=sunEndFog(d,SUN_END_FOG_REACH,light,l);
    // The bodies are far beyond the fog in spirit: it stays in the empty sky and only softens them, at any fog setting.
    float through=solid*.82;
    return color*mix(air.transmittance,1.0,through)+air.scatter*(1.0-through);
}
static float3 sunRainbowHue(float x) { return saturate(abs((x*6.0+float3(-0.55,4.3,2.2))-6.0*floor((x*6.0+float3(-0.55,4.3,2.2))/6.0)-3.0)-1.0); }
// Stable ray/reference-shell intersection, shared by all height samples. H/R = curvature.
// A smooth angular window restores planar projection above the chosen elevation. The same
// weight extends visibility to the horizon, avoiding the old shared 0.15-elevation cutoff.
// The affine sample stack is retained as a cheap curved-shell approximation, not a planet mesh.
static float2 sunAuroraProjection(float up,float curvature,float inverseExtent) {
    if(curvature<=0)return float2(1.0/up,0);
    float weight=1.0-smoothstep(0.0,1.0,up*inverseExtent);
    if(weight<=0)return float2(1.0/up,0);
    float k=curvature*weight;
    float distance=(2.0+k)/(sqrt(up*up+k*(2.0+k))+up);
    return float2(distance,weight);
}
// Integral of the narrow noise ridge, powers 8 and 16. Box filtering the sample's
// height/pixel footprint removes both screen-space grain and discrete layer rings.
static float2 sunAuroraRidgeIntegral(float n) {
    float r=saturate(1.0-2.0*abs(n-.5)),r2=r*r,r4=r2*r2,r8=r4*r4;
    float2 primitive=float2(r8*r*(1.0/18.0),r8*r8*r*(1.0/34.0));
    return n<.5?primitive:float2(1.0/9.0,1.0/17.0)-primitive;
}
static float sunAuroraRidge(float n,float width,float soft) {
    // A tiny lower bound keeps division stable. Use the point value at magnification
    // to avoid cancellation when subtracting nearly equal primitive values.
    if(width<.002) {
        float r=1.0-2.0*abs(n-.5),r2=r*r,r4=r2*r2,r8=r4*r4;
        return mix(r8*r8,r8,soft);
    }
    float2 average=(sunAuroraRidgeIntegral(n+width*.5)-sunAuroraRidgeIntegral(n-width*.5))/width;
    return max(0.0,mix(average.y,average.x,soft));
}
// Logical auroras: one per cell of a coarse lattice over the aurora plane, switched on in a fixed random order.
// shown = count/10. A higher count only switches further cells on and never changes one already on, so auroras are
// added, never shrunk. Columns of the same plane cell across the layers form one consistent curtain.
// One read of the packed noise cell gives the ranks of all four surrounding lattice corners. A corner is on while its
// rank is below shown = count/10, with a fixed brightness of its own, so a higher count only switches further auroras
// on and never changes one already on. The lattice is turned 30 degrees off the plane axes so no edge runs straight.
static float sunAuroraRegions(float2 q,float shown,texture2d<float> noiseTexture) {
    float2 p=float2(q.x*.8660254-q.y*.5,q.x*.5+q.y*.8660254)*(1.0/14.0),i=floor(p),f=p-i;f=f*f*(3.0-2.0*f);
    float4 rank=noiseTexture.read(uint2(int2(i)&int2(127)),0);
    float4 corner=select(float4(0),.6+.4*fract(rank*7.31),rank<shown);
    return smoothstep(.15,.55,mix(mix(corner.x,corner.y,f.x),mix(corner.z,corner.w,f.x),f.y));
}
// parts: 1 the aurora (the half-resolution pass), 2 the rainbow (the sky pixels). The nebula is apart (sunNebula).
static float3 sunAtmosphere(float3 d,float2 pixel,float pixelAngle,constant SunFrame &sun,constant SunLighting &l,texture2d<float> noiseTexture,uint parts=3u) {
    if(!SUN_SKY_FX)return float3(0);
    float aurora=(parts&1u)?l.p[6].w:0.0,rainbow=(parts&2u)?l.p[7].y:0.0;
    if(aurora==0.0&&rainbow<=0.0)return float3(0);
    float3 color=float3(0);float clock=l.p[18].x;
    if(aurora!=0.0&&l.aurora[1].w>0&&d.y>(l.aurora[0].y>0?0.0:0.15)&&l.aurora[0].w>0) {
        // Complementary-style sample stack, optionally bent over a virtual sky shell.
        float packed=max(l.p[8].w,0.0),code=floor(packed*.5),soft=saturate(packed-code*2),depth=code>0?code*0.05:1;
        bool blocky=aurora<0;
        float time=clock*l.aurora[2].z;
        // Rotate the horizontal plane under the whole upper hemisphere, including the zenith.
        float2 projection=sunAuroraProjection(d.y,l.aurora[0].y,l.aurora[1].y);
        float up2=d.y*d.y,zenith=up2*up2;
        // Finer structure overhead; gently bend the upper samples sideways so they no
        // longer all collapse onto the same texel when the view points straight up.
        float2 plane=float2(dot(d.xz,l.aurora[1].xz),-dot(d.xz,l.aurora[0].xz))*projection.x*l.aurora[0].w*(1.0+1.5*zenith);
        float2 bend=float2(12,-8)*(depth*zenith);
        // Conservative angular footprint, independent of neighboring terrain/sky pixels.
        // Quad derivatives inside the sky-only branch are undefined at silhouettes and
        // produced seams; this bound also works with dynamic resolution and view bobbing.
        float planeFoot=pixelAngle*l.aurora[0].w*(1.0+1.5*zenith)*projection.x*max(projection.x,1.0)*.0007;
        float bendFoot=pixelAngle*57.7*depth*.0007;
        // Only the curved region replaces the old angular cutoff with a gentle horizon fade.
        float visibility=saturate(d.y*1.5-.225);
        if(projection.y>0) {
            float fade=smoothstep(0.0,0.15,d.y);
            visibility=mix(visibility,saturate(d.y*1.5)*fade*fade,projection.y);
        }
        float2 start=plane*8.8+float2(time*.04,0),span=plane*(depth*11);
        // count/10 squared is the share of lattice corners switched on: soft region edges make a linear share look full
        // early, while the square spreads the steps from a few single auroras (1 %) to the whole sky (10: no mask).
        float shown=l.aurora[1].w<1.0?l.aurora[1].w*l.aurora[1].w:1.0;
        float visible=abs(aurora)*sqrt(visibility)*(1-d.y*.9);
        if(visible>0) {
            // Uniform CPU budget, inverse and speed; shared affine plane transform outside the loop.
            int count=clamp(int(l.aurora[2].x),10,25);float inverse=l.aurora[2].y;
            float animate=time*.001,step=25*inverse,heightDelta=step*(.0007*128.0/15.0);
            constexpr sampler noiseSampler(coord::normalized,address::repeat,filter::linear,mip_filter::linear);
            // The two colors (SunAurora), scaled to the sample stack's brightness.
            float3 curtain=l.sky[0].rgb*16.0,crown=l.sky[1].rgb*12.0,sum=float3(0);
            for(int i=0;i<count;i++) {
                // Stable midpoint quadrature replaces screen-pixel jitter, which showed as dots.
                // Same integration interval at every quality, with no extra height samples.
                float height=(5.0+(float(i)+0.5)*step)/30.0,layer=height*height;
                float bow=layer*(1.0-layer),scale=8.8+depth*11.0*layer;
                float2 q=fma(span,float2(layer),start)+bend*bow;
                // Logical auroras (count 1..10): outside every switched-on one the layer costs this single read and no
                // more. Ten: no mask, the whole Complementary sky.
                float region=shown<1.0?sunAuroraRegions(q,shown,noiseTexture):1.0;
                if(region<=0)continue;
                float2 uv=(blocky?floor(q):q)*.0007;
                float pixelFoot=planeFoot*scale+bendFoot*bow;
                // RGBA holds the four corners of a periodic scalar-noise cell. One read
                // gives a C1 interpolated value and its exact local gradient, without extra taps.
                float2 cell=uv*128.0-.5,at=floor(cell),f=cell-at,w=f*f*(3.0-2.0*f),dw=6.0*f*(1.0-f);
                float4 corners=noiseTexture.read(uint2(int2(at)&int2(127)),0);
                float lo=mix(corners.r,corners.g,w.x),hi=mix(corners.b,corners.a,w.x);
                float noise=mix(lo,hi,w.y);
                float2 gradient=float2(mix(corners.g-corners.r,corners.a-corners.b,w.y)*dw.x,(hi-lo)*dw.y);
                float2 layerDelta=(span+bend*(1.0-2.0*layer))*(height*heightDelta);
                // The isotropic pixel footprint: |g.dx|+|g.dy| with dx=(foot,0), dy=(0,foot) in texels.
                float footprint=abs(dot(gradient,layerDelta))+(abs(gradient.x)+abs(gradient.y))*(pixelFoot*128.0);
                float band=sunAuroraRidge(noise,footprint,soft)*region;
                if(band<=0)continue;
                // The footprint is isotropic, so the gradient's level of detail is exactly log2 of its texel size.
                // An explicit level samples at full rate; gradient sampling is slower on Apple GPUs.
                float lod=log2(max(pixelFoot*128.0,1e-20));
                float detail;
                if(blocky){detail=noiseTexture.sample(noiseSampler,uv*100+animate,level(lod+6.64385619)).b;band*=detail*sqrt(detail);}
                else detail=noiseTexture.sample(noiseSampler,uv*3+animate,level(lod+1.58496250)).b;
                if(!blocky)band*=detail*noiseTexture.sample(noiseSampler,uv*5-animate,level(lod+2.32192809)).b;
                float fade=1-layer,f2=fade*fade;
                sum+=band*fade*mix(crown,curtain,f2*f2);
            }
            color+=sum*((blocky?1.3:1.8)*inverse)*visible;
        }
    }
    if(rainbow>0.0) {
        // Opposite the sun, about 41 degrees from its antisolar point (Complementary's VdotL band at -0.75), its width
        // scaled by the diameter setting.
        float diameter=l.p[7].x<0.0?-l.p[7].x:1.0;
        float coord=saturate(1.0-(dot(d,sunToward(sun,l))+0.75)/(0.0625*diameter));
        float band=coord*(1.0-coord)*3.7;band*=band;band*=band;band*=rainbow*saturate(1.0+d.y*4.0);
        if(band>0.0) {
            float dither=fract(52.9829189*fract(dot(floor(pixel),float2(0.06711056,0.00583715))));
            float m=pow(coord,1.4+max(coord-0.5,0.0)*1.6);m=smoothstep(0.0,1.0,m)*0.85+(dither-0.5)*0.1;
            float3 bow=(sunRainbowHue(m)+sunRainbowHue(m+0.1)+sunRainbowHue(m-0.1))/3.0;
            bow.r+=max(coord-0.5,0.0)*max(coord-0.5,0.0)*max(1.0-coord,0.0)*26.0;
            color+=pow(bow,2.2)*float3(0.25,0.075,0.25)*3.0*band;
        }
    }
    return color;
}
// The night nebula, only in the half-resolution pass: filtered reads of the sky noise (its four channels), never a loop per pixel.
static float3 sunNebula(float3 d,constant SunFrame &sun,constant SunLighting &l,texture2d<float> noiseTexture) {
    float nebula=max(l.p[7].x,0.0);
    if(!SUN_SKY_FX||nebula<=0.0)return float3(0);
    constexpr sampler noiseSampler(coord::normalized,address::repeat,filter::linear,mip_filter::linear);
    float3 color=float3(0);float clock=l.p[18].x;
    if(nebula>0.0&&d.y>0.0) {
        float fade=smoothstep(.02,.38,d.y);
        // Stereographic: no pinch at the zenith. Four filtered reads carry the whole nebula, at half resolution.
        float2 uv=d.xz/(1.0+d.y)*(.62/max(l.sky[2].w,.05));
        float drift=clock*.00055*l.sky[3].w;
        float4 shape=noiseTexture.sample(noiseSampler,uv*.3+float2(drift*.7,-drift*.4),level(1.0));
        float2 warped=uv+(shape.xy-.5)*.34;
        half cloud=half(noiseTexture.sample(noiseSampler,warped*.55-float2(drift*.35,0),level(1.0)).g);
        half wisp=half(noiseTexture.sample(noiseSampler,warped*1.4+float2(0,drift*.8),level(1.0)).b);
        half body=smoothstep(.40h,.82h,cloud+(wisp-.5h)*.34h);
        half tint=smoothstep(.34h,.66h,half(shape.z));
        half3 hue=mix(half3(l.sky[2].rgb),half3(l.sky[3].rgb),tint);
        color+=float3(hue*body*body)*.36*nebula*fade;
    }
    return color;
}
// The same camera-to-surface air segment for terrain, objects, transparent surfaces and water.
// Mix linear scene-image values; HDR keeps radiance until the final camera tone map.
// Original keeps vanilla fog endpoints intact. No receiver light/shadow term belongs in atmospheric extinction.
// Extinction only: trace culling must not evaluate the sky color.
static float sunFogTransmission(float vanilla,float3 p,float visibility,constant Fog &fog,constant SunLighting &l) {
    float amount=saturate(vanilla);
    if(sunOwnFog(l,fog)){float3 delta=p-float3(l.pad0,l.pad1,l.hand[2].w);amount=max(amount,sunDimension(l)==0?sunHaze(length(delta),delta.y,l):sunDimensionHaze(delta,l));}
    return (1.0-saturate(amount))*saturate(visibility);
}
struct SunFog { float3 radiance;float transmittance; };
static SunFog sunFog(float vanilla,float3 p,float visibility,constant Fog &fog,constant SunFrame &sun,constant SunLighting &l) {
    float3 eye=float3(l.pad0,l.pad1,l.hand[2].w),delta=p-eye;
    float distance=length(delta);bool native=sunOwnFog(l,fog);
    SunFog result;result.transmittance=sunFogTransmission(vanilla,p,visibility,fog,l);
    result.radiance=float3(0);
    if(native&&sunDimension(l)>0) {
        // As the render border closes, the ray reaches on as a sky ray does, so terrain ends in the sky's own air.
        float3 view=delta/max(distance,1e-4),light=sunEndLight(sun,l);
        float near=1.0-sunDimensionHaze(delta,l),clear=result.transmittance;
        SunEndAir air=sunEndFog(view,mix(SUN_END_FOG_REACH,distance,near),light,l);
        result.transmittance=clear*air.transmittance;
        if(result.transmittance>=1.0)return result;
        float3 behind=clear<1.0?sunEndAir(view,light,l)*air.transmittance*(1.0-clear):float3(0);
        result.radiance=sunToneMap((air.scatter+behind)/max(1.0-result.transmittance,1e-4),l);
        return result;
    }
    if(result.transmittance>=1.0)return result;
    result.radiance=pow(max(fog.FogColor.rgb,0.0),2.2);
    if(native&&sunDimension(l)!=0) {
        float3 direction=delta/max(distance,1e-4);
        result.radiance=sunToneMap(sunDimensionSky(direction,l),l);
        return result;
    }
    float skyBlend=saturate(l.p[16].z)*sunFogExposure(l);
    if(native&&skyBlend>0.0&&result.transmittance<1.0-SUN_HAZE_UNSEEN) {
        float3 view=delta/max(distance,1e-4),d=view;d.y=max(d.y,0.0)*0.5;
        d*=rsqrt(max(dot(d,d),1e-8));
        // Glow and sunset band belong to fog thick enough to stand in for the sky: only there nothing shows through it.
        float thick=1.0-result.transmittance,thick2=thick*thick;
        result.radiance=mix(result.radiance,sunToneMap(sunSkyLinear(d,view,thick2*thick2*thick2,sun,l),l),skyBlend);
    }
    return result;
}
static float3 sunFogScene(float3 lit,float vanilla,float3 p,float visibility,constant Fog &fog,constant SunFrame &sun,constant SunLighting &l) {
    SunFog air=sunFog(vanilla,p,visibility,fog,sun,l);
    float3 scene=sunToneMap(lit,l);
    return air.transmittance>=1.0?scene:mix(air.radiance,scene,air.transmittance);
}
// A late surface edit must leave the already composed foreground air intact in the same linear scene space.
static float3 sunFogEditScene(float3 scene,float3 add,float remove,SunFog air,constant SunLighting &l) {
    if(air.transmittance<=0)return scene;
    if(air.transmittance>=1.0)return max(scene*(1-remove)+add,0.0);
    float3 fogLayer=air.radiance*(1-air.transmittance);
    return max(scene-max(scene-fogLayer,0.0)*remove+add*air.transmittance,0.0);
}
// Automatic exposure: one threadgroup (up to 1,024 threads) reads 64x32 points of the finished world image (centre-weighted) and writes the
// average log2 display luminance, the log2 display luminance middle grey should have, and the log2 automatic factor
// the image was made with. The CPU adapts from that (mcsun_exposure.inc). info: x HDR scene radiance, y sRGB target,
// z frame tag.
kernel void sun_exposure_measure(texture2d<float,access::read> scene [[texture(0)]],constant SunLighting &l [[buffer(0)]],
    constant uint4 &info [[buffer(1)]],device float4 *out [[buffer(2)]],uint lane [[thread_index_in_threadgroup]],uint threads [[threads_per_threadgroup]],
    uint simd [[simdgroup_index_in_threadgroup]],uint simds [[simdgroups_per_threadgroup]],uint simdLane [[thread_index_in_simdgroup]]) {
    threadgroup float2 partial[32];
    const float3 weights=float3(0.2126,0.7152,0.0722);
    float2 size=float2(scene.get_width(),scene.get_height()),sum=0;
    for(uint point=lane;point<2048u;point+=threads) {
        float2 uv=(float2(point&63u,point>>6)+0.5)/float2(64,32);
        float3 c=max(scene.read(uint2(uv*size)).rgb,0.0);
        c=info.x?sunToneMapFinal(c,l):info.y?c:pow(c,2.2);
        float2 d=(uv-0.5)*float2(2.0,1.6);float w=1.0+3.0*exp(-2.5*dot(d,d));
        sum+=float2(log2(max(dot(c,weights),1e-4))*w,w);
    }
    sum=simd_sum(sum);if(simdLane==0)partial[simd]=sum;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if(lane==0) {
        float2 total=0;for(uint i=0;i<simds;i++)total+=partial[i];
        float grey=dot(sunGrade(sunToneCurve(float3(0.18*l.exposure)*l.grade[0].xyz,l),l),weights);
        out[0]=float4(total.x/total.y,log2(max(grey,1e-4)),log2(max(l.grade[0].w,1e-6)),as_type<float>(info.z));
    }
}
// A sky pixel (no terrain, cleared depth): the native sky, keeping vanilla's sun, moon and stars.
// 63: auroras come from the half-resolution pass (sun_aurora_fs) instead of being evaluated per pixel. Undefined: off.
constant bool SUN_AURORA_LOW_SET [[function_constant(63)]];
constant bool SUN_AURORA_LOW=is_function_constant_defined(SUN_AURORA_LOW_SET)&&SUN_AURORA_LOW_SET;
static float3 sunSkyDirection(float2 uv,constant SunTemporalFrame &frame) {
    float2 ndc=uv*2-1;float4 near=frame.inverseView*float4(ndc,1,1),far=frame.inverseView*float4(ndc,0.25,1);
    return normalize(far.xyz/far.w-near.xyz/near.w);
}
// The angle one pixel covers around this view direction.
static float sunSkyPixelSize(float2 uv,float3 direction,constant SunTemporalFrame &frame) {
    return max(length(sunSkyDirection(uv+float2(1.0/frame.config.x,0),frame)-direction),length(sunSkyDirection(uv+float2(0,1.0/frame.config.y),frame)-direction));
}
// Auroras at half resolution per axis: soft by design, so a quarter of the pixels carries them and the lighting passes
// sample this target bilinearly. A texel is skipped when no sky pixel lies within its bilinear reach (its 2x2 pixels
// and a one-pixel border), so sky next to terrain never reads an unwritten texel.
fragment half4 sun_aurora_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],constant SunFrame &sun [[buffer(1)]],
    constant SunLighting &lighting [[buffer(4)]],depth2d<float> depth [[texture(3)]],texture2d<float> auroraNoise [[texture(32)]],texture2d<float> skyNoise [[texture(34)]]) {
    int2 full=int2(frame.config.xy),base=int2(in.position.xy)*2;bool sky=false;
    for(int y=-1;y<=2;y++)for(int x=-1;x<=2;x++)sky=sky||depth.read(uint2(clamp(base+int2(x,y),int2(0),full-1)))<=0;
    if(!sky)return half4(0);
    float2 uv=(float2(base)+1.0)/frame.config.xy;
    // The texel spans two pixels: twice the per-pixel footprint keeps the aurora's own filtering grain-free.
    float3 direction=sunSkyDirection(uv,frame);
    return half4(half3(sunAtmosphere(direction,float2(base),6.0/min(frame.config.x,frame.config.y),sun,lighting,auroraNoise,1u)+sunNebula(direction,sun,lighting,skyNoise)),1);
}
// Analytic silhouettes in the existing sky resolve: no additional target, pass, history or texture upload.
// Off is specialized out of production lighting pipelines, including its temporary registers.
constant bool SUN_CELESTIAL_SET [[function_constant(64)]];
constant bool SUN_CELESTIAL=!is_function_constant_defined(SUN_CELESTIAL_SET)||SUN_CELESTIAL_SET;
struct SunCelestialSample { float3 radiance,halo;float coverage; };
struct SunCelestialLayers { SunCelestialSample solar,lunar;float visibility; };
static SunCelestialSample sunCelestialBody(float3 direction,float pixelAngle,float3 toward,float4 body,float shape,float glow,float phase,float surface,float3 phaseControl,bool lunar,constant SunLighting &l) {
    SunCelestialSample result={float3(0),float3(0),0};
    if(max(body.r,max(body.g,body.b))<=0.0)return result;
    float facing=dot(direction,toward);if(facing<=0.0)return result;
    float radius=max(body.w,0.001),scale=tan(radius);
    float3 right=normalize(cross(float3(0,0,1),toward)),up=cross(toward,right);
    float2 q=float2(dot(direction,right),dot(direction,up))/(scale*facing);
    float r2=dot(q,q);if(r2>36.0)return result;
    float metric=shape<.5?sqrt(r2):shape<1.5?max(abs(q.x),abs(q.y)):abs(q.x)+abs(q.y);
    // The silhouette stays about one pixel wide at every size/FOV/resolution. From the camera, not fwidth: only sky pixels get
    // here, and next to a leaf or a blade of grass the screen derivative is undefined. It made those pixels half white.
    float pixelQ=pixelAngle/(scale*facing),edgeAA=max(pixelQ*.72,0.0001),disc=1.0-smoothstep(1.0-edgeAA,1.0+edgeAA,metric);
    float illumination=1.0;
    if(phase>=0.0) {
        // A smooth square-to-disk map avoids both flat corner normals and the central kink of radial normalization.
        float2 disk=q;
        if(shape>.5) {
            float2 square=shape<1.5?q:float2(q.x+q.y,q.y-q.x);
            float2 warped=square*sqrt(max(float2(0),1.0-.5*square.yx*square.yx));
            disk=shape<1.5?warped:float2(warped.x-warped.y,warped.x+warped.y)*0.70710678118;
        }
        float x=dot(disk,phaseControl.xy),y=dot(disk,float2(-phaseControl.y,phaseControl.x));
        float c=cos(phase),s=sin(phase),limb=sqrt(max(0.0,1.0-y*y));
        // Signed distance to the projected terminator, rather than a thresholded surface normal.
        float distance=(s>=0.0?x:-x)+c*limb;
        float transition=max(pixelQ*.72*(1.0+abs(c*y)/max(limb,.2)),phaseControl.z*.45*abs(s));
        illumination=smoothstep(-max(transition,.0001),max(transition,.0001),distance);
        if(c>0.999999)illumination=1.0;
        if(c<-.999999)illumination=0.0;
    }
    float detail=mix(1.0,0.55+0.45*sunSkyNoise(q*9.0+float2(19,7)),surface);
    float halo=glow*0.12*exp2(-r2*0.8)*(1.0-disc);
    float phaseLight=phase<0.0?1.0:0.5+0.5*cos(phase);
    if(lunar) {
        // A small fixed quadrature of genuinely lit positions, shared with flares and volumetric scattering.
        // Unlike a round halo times phase brightness, this moves the glow with the crescent and its rotation.
        float source=0;
        if(glow>0.0&&l.celestial[5].z>0.0)for(uint i=0;i<8;i++){float4 point=l.celestial[6+i];float2 delta=q-point.xy;source+=point.z*exp2(-dot(delta,delta)*.8);}
        halo=glow*.12*source*(1.0-disc*illumination);phaseLight=1.0;
    }
    result.radiance=body.rgb*detail;result.coverage=disc*illumination;result.halo=body.rgb*(halo*phaseLight);
    return result;
}
static SunCelestialLayers sunCelestialLayers(float3 direction,float pixelAngle,constant SunFrame &sun,constant SunLighting &l) {
    SunCelestialSample empty={float3(0),float3(0),0};SunCelestialLayers result={empty,empty,0};
    if(!SUN_CELESTIAL||l.celestial[3].z<.5)return result;
    float3 toward=sunToward(sun,l);float horizon=l.celestial[3].x;
    float4 solar=l.celestial[0];
    solar.rgb*=mix(float3(1),float3(1,.38,.12),horizon*(1.0-smoothstep(0.0,.3,toward.y)));
    result.solar=sunCelestialBody(direction,pixelAngle,toward,solar,l.celestial[3].z-1,l.celestial[2].x,-1,0,float3(1,0,0),false,l);
    result.lunar=sunCelestialBody(direction,pixelAngle,-toward,l.celestial[1],l.celestial[3].w,l.celestial[2].y,l.celestial[2].z,l.celestial[2].w,l.celestial[4].xyz,true,l);
    result.visibility=smoothstep(-.02,.01,direction.y)*mix(1.0,l.celestial[3].y,l.env.y)*(1.0-sunThunderDarken(l));
    return result;
}
// Premultiplied scene radiance for isolated image checks; production composites coverage separately below.
static float3 sunCelestialBodies(float3 direction,float pixelAngle,constant SunFrame &sun,constant SunLighting &l) {
    SunCelestialLayers p=sunCelestialLayers(direction,pixelAngle,sun,l);
    return (p.solar.radiance*p.solar.coverage+p.solar.halo+p.lunar.radiance*p.lunar.coverage+p.lunar.halo)*p.visibility;
}
static float3 sunSkyPixel(float3 color,float2 uv,float depth,constant SunTemporalFrame &frame,constant SunFrame &sun,
    constant Fog &fog,constant SunLighting &lighting,texture2d<float> noiseTexture,texture2d<float> auroraLow) {
    if(!lighting.enabled||depth>0||!sunOwnFog(lighting,fog))return color;
    float3 direction=sunSkyDirection(uv,frame);
    if(sunDimension(lighting)!=0) {
        float3 radiance=sunDimension(lighting)>0?sunEndSky(direction,length(sunSkyDirection(uv+float2(0,1.0/frame.config.y),frame)-direction),sun,lighting):sunDimensionSky(direction,lighting);
        float3 display=sunDisplay(radiance,lighting);
        return lighting.srgb?pow(display,2.2):display;
    }
    float3 vanilla=lighting.srgb?pow(color,1.0/2.2):color;
    float3 features=max(vanilla-float3(fog.FogColor.rgb)*1.05,0.0)*lighting.p[15].w;
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float pixelAngle=3.0/min(frame.config.x,frame.config.y);
    float3 atmosphere=SUN_AURORA_LOW?sunAtmosphere(direction,uv*frame.config.xy,pixelAngle,sun,lighting,noiseTexture,2u)+auroraLow.sample(linear,uv).rgb
        :sunAtmosphere(direction,uv*frame.config.xy,pixelAngle,sun,lighting,noiseTexture);
    if(SUN_SKY_FX&&lighting.sky[1].w>0.0&&direction.y>0.0)atmosphere+=sunNightStars(direction,length(sunSkyDirection(uv+float2(0,1.0/frame.config.y),frame)-direction),sun,lighting);
    float3 sky=sunDisplay(sunSkyLinear(direction,sun,lighting)+atmosphere,lighting)+features;
    float3 display=mix(vanilla,lighting.materials[3].w>.5?max(sky,0.0):saturate(sky),lighting.p[15].z);
    // The unlit moon is transparent sky, not black radiance passed through a tone curve. Blend the lit
    // coverage after grading, in linear image space, so lift/gamma, HDR and colored skies cannot create a dark disk.
    float pixelSize=0;
    if(SUN_CELESTIAL&&lighting.celestial[3].z>.5)pixelSize=sunSkyPixelSize(uv,direction,frame);
    SunCelestialLayers bodies=sunCelestialLayers(direction,pixelSize,sun,lighting);
    float solarAlpha=bodies.solar.coverage*bodies.visibility,lunarAlpha=bodies.lunar.coverage*bodies.visibility;
    float3 halo=(bodies.solar.halo+bodies.lunar.halo)*bodies.visibility;
    if(solarAlpha>0.0||lunarAlpha>0.0||any(halo>0.0)) {
        float3 linear=pow(max(display,0.0),2.2);
        if(solarAlpha>0.0)linear=mix(linear,sunToneMap(bodies.solar.radiance,lighting),solarAlpha);
        if(lunarAlpha>0.0)linear=mix(linear,sunToneMap(bodies.lunar.radiance,lighting),lunarAlpha);
        // Remove the tone curve's black point from additive halos; grading lift belongs to the background once.
        if(any(halo>0.0))linear+=max(sunToneMap(halo,lighting)-sunToneMap(float3(0),lighting),0.0);
        return lighting.srgb?linear:pow(max(linear,0.0),1.0/2.2);
    }
    return lighting.srgb?pow(display,2.2):display;
}
// Water/forward colors are already in scene-image space. Decode, mix the same fog layer in linear space,
// and preserve that encoding. Original's already displayed transmission is not tone-mapped a second time.
static float3 ownWaterFog(float3 rgb,float vanilla,float3 p,float visibility,constant Fog &fog,constant SunFrame &sun,constant SunLighting &l) {
    SunFog air=sunFog(vanilla,p,visibility,fog,sun,l);
    if(air.transmittance>=1.0)return rgb;
    float3 scene=l.srgb?rgb:pow(max(rgb,0.0),2.2);
    float3 mixed=mix(air.radiance,scene,air.transmittance);
    return l.srgb?mixed:pow(max(mixed,0.0),1.0/2.2);
}
// Terrain pixel color for the shadow resolve passes. Off (no G-buffer, or its vanilla comparison
// view): the previous darkening of the vanilla color.
static float3 sunTerrainColor(float3 color,float3 visible,float fogAmount,uint2 pixel,float3 position,constant SunFrame &sun,constant Fog &fog,
    constant SunLighting &lighting,texture2d<float> gAlbedo,texture2d<float> gLight,texture2d<float> gNormal,device const uint *colored=nullptr) {
    if(!lighting.enabled) {
        color*=mix(float3(1),0.45+0.55*visible,sun.sun.w);
        return mix(color,fog.FogColor.rgb,fogAmount);
    }
    float4 base=gAlbedo.read(pixel),data=gLight.read(pixel);float3 normal=gNormal.read(pixel).xyz;
    normal*=rsqrt(max(dot(normal,normal),1e-12));
    // G-buffer light values are lightmap coordinates (0.5/16 .. 15.5/16).
    float block=saturate((data.x*16.0-0.5)/15.0),sky=saturate((data.y*16.0-0.5)/15.0);
    float3 lit=sunTerrainLight(pow(base.rgb,2.2),block,sky,normal,visible,position,sun,lighting,colored);
    SunWet wet=sunWetness(block,sky,normal,position,sun,lighting);bool wetShaded;
    lit=sunSurfaceMaterial(sunWetDarken(lit,wet),base.rgb,uint(round(data.z*255)),sky,normal,visible,position,sun,lighting,wet,wetShaded);
    if(!wetShaded)lit=sunWetSheen(lit,wet,sky,visible,position,sun,lighting);
    float3 scene=sunFogScene(lit,fogAmount,position,data.w,fog,sun,lighting);
    // sRGB targets store linear values; the hardware encodes on write.
    return lighting.srgb?scene:pow(max(scene,0.0),1.0/2.2);
}
// Shared current visibility for the regular mask and the no-history direct pass.
static float4 sunCurrentMask(float2 fragmentPosition,constant SunTemporalFrame &frame,constant SunFrame &sun,
    constant SunBlendFrame &blend,constant SunGlassFrame &glass,texture2d<float> normals,depth2d<float> depth,
    array<depth2d<float>,4> shadows,array<depth2d<float>,4> glassDepth,array<texture2d<float>,4> glassColor,
    sampler shadowSampler,thread float3 &position) {
    uint2 pixel=uint2(fragmentPosition);float4 surface=normals.read(pixel);position=float3(0);
    // Other world surfaces/background are untouched by the terrain-only resolve.
    if(surface.w<0)return float4(1,1,1,surface.w);
    float2 uv=(float2(pixel)+.5)/frame.config.xy;
    float4 point=frame.inverseView*float4(uv*2-1,depth.read(pixel),1);
    position=point.xyz/point.w;
    // No direct sun (night, or past the sunset fade): the shadow maps are not rendered this frame.
    if(sun.sun.w<=0)return float4(1,1,1,surface.w);
    float3 normal=surface.xyz*rsqrt(max(dot(surface.xyz,surface.xyz),1e-12));
    float3 visible=sunSurfaceLighting(point.xyz/max(point.w,1e-8),normal,fragmentPosition,sun,blend,glass,
        shadows,glassDepth,glassColor,shadowSampler,frame.config.z<1);
    return float4(visible,surface.w);
}
// Shadows at half resolution (Light → Performance): the mask pass evaluates one pixel per 2x2 block. The temporal
// pass blends the four nearest blocks, weighted toward blocks of its own surface kind and never taking sky or other
// non-terrain blocks, and clamps its history against the neighbouring blocks. Off: the full-resolution path, unchanged.
constant bool SUN_HALF_SET [[function_constant(31)]];
constant bool SUN_HALF=is_function_constant_defined(SUN_HALF_SET)&&SUN_HALF_SET;
static float4 sunHalfMask(texture2d<half> current,texture2d<float> surfaces,uint2 pixel) {
    float own=surfaces.read(pixel).w;
    if(own<0)return float4(1,1,1,own);
    int2 last=int2(current.get_width(),current.get_height())-1;
    float2 h=(float2(pixel)+.5)*.5-.5;int2 base=int2(floor(h));float2 t=h-floor(h);
    float3 sum=0;float total=0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        half4 v=current.read(uint2(clamp(base+int2(x,y),int2(0),last)));
        float w=(x?t.x:1-t.x)*(y?t.y:1-t.y)*(v.w<0?0.0:abs(float(v.w)-own)<.5?1.0:.05);
        sum+=float3(v.rgb)*w;total+=w;
    }
    return float4(total>1e-4?sum/total:float3(current.read(uint2(clamp(int2(pixel/2),int2(0),last))).rgb),own);
}
static uint2 sunMaskNeighbour(uint2 pixel,int2 offset,uint2 size,texture2d<half> current) {
    if(SUN_HALF)return uint2(clamp(int2(pixel/2)+offset,int2(0),int2(current.get_width(),current.get_height())-1));
    return uint2(clamp(int2(pixel)+offset,int2(0),int2(size)-1));
}
fragment float4 sun_deferred_mask_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant SunBlendFrame &blend [[buffer(2)]],constant SunGlassFrame &glass [[buffer(3)]],
    texture2d<float> normals [[texture(0)]],depth2d<float> depth [[texture(1)]],
    array<depth2d<float>,4> shadows [[texture(20)]],array<depth2d<float>,4> glassDepth [[texture(24)]],
    array<texture2d<float>,4> glassColor [[texture(28)]],sampler shadowSampler [[sampler(13)]]) {
    float2 at=SUN_HALF?min(floor(in.position.xy)*2+.5,frame.config.xy-.5):in.position.xy;
    float3 position;return sunCurrentMask(at,frame,sun,blend,glass,normals,depth,shadows,glassDepth,glassColor,shadowSampler,position);
}
struct SunHistoryOutput { float4 color [[color(0)]];float4 history [[color(1)]]; };
// Wind above its clear-weather strength (OwnWind.Clock: 1 clear, 1.75 in a full storm), 0..1. The cascade frame carries the
// strength in camBlock.w (OwnWind.packet); 0 with wind off.
static float sunWindGust(constant SunFrame &sun) { return saturate((as_type<float>(sun.camBlock.w)-1.0)/0.75); }
fragment SunHistoryOutput sun_deferred_apply_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant SunBlendFrame &blend [[buffer(2)]],constant SunGlassFrame &glass [[buffer(3)]],
    constant Fog &fog [[buffer(24)]],constant SunLighting &lighting [[buffer(4)]],texture2d<float> gAlbedo [[texture(5)]],texture2d<float> gLight [[texture(6)]],texture2d<float> gNormal [[texture(7)]],texture2d<float> normals [[texture(0)]],depth2d<float> depth [[texture(1)]],texture2d<float> scene [[texture(2)]],
    array<depth2d<float>,4> shadows [[texture(20)]],array<depth2d<float>,4> glassDepth [[texture(24)]],
    array<texture2d<float>,4> glassColor [[texture(28)]],sampler shadowSampler [[sampler(13)]],texture2d<float> auroraNoise [[texture(32)]],device const uint *colored [[buffer(28)]],texture2d<float> auroraLow [[texture(33)]]) {
    float3 position;float4 mask=sunCurrentMask(in.position.xy,frame,sun,blend,glass,normals,depth,shadows,glassDepth,glassColor,shadowSampler,position);
    // Preserve the original RGBA16Float intermediate's quantization exactly.
    float3 visible=float3(half3(mask.rgb));float4 color=scene.read(uint2(in.position.xy));float distance=0;
    if(mask.w>=0){distance=length(position);color.rgb=sunTerrainColor(color.rgb,visible,mask.w,uint2(in.position.xy),position,sun,fog,lighting,gAlbedo,gLight,gNormal,colored);}
    else color.rgb=sunSkyPixel(color.rgb,in.position.xy/frame.config.xy,depth.read(uint2(in.position.xy)),frame,sun,fog,lighting,auroraNoise,auroraLow);
    SunHistoryOutput result;result.color=color;result.history=float4(visible,distance);return result;
}

fragment SunHistoryOutput sun_temporal_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant Fog &fog [[buffer(24)]],constant SunLighting &lighting [[buffer(4)]],texture2d<float> gAlbedo [[texture(5)]],texture2d<float> gLight [[texture(6)]],texture2d<float> gNormal [[texture(7)]],texture2d<float> scene [[texture(0)]],texture2d<half> current [[texture(1)]],
    texture2d<float> history [[texture(2)]],depth2d<float> depth [[texture(3)]],texture2d<float> surfaces [[texture(8)]],texture2d<float> auroraNoise [[texture(32)]],device const uint *colored [[buffer(28)]],texture2d<float> auroraLow [[texture(33)]]) {
    uint2 pixel=uint2(in.position.xy),size=uint2(frame.config.xy);
    float2 uv=(float2(pixel)+0.5)/frame.config.xy;
    float4 mask=SUN_HALF?sunHalfMask(current,surfaces,pixel):float4(current.read(pixel)),color=scene.read(pixel);
    float3 visible=mask.rgb;float distance=0;
    if(mask.w>=0) {
        float z=depth.read(pixel);
        float4 point=frame.inverseView*float4(uv*2.0-1.0,z,1);
        float3 position=point.xyz/point.w;distance=length(position);
        float3 previousPosition=position+frame.cameraDelta.xyz;
        float4 previous=frame.previousView*float4(previousPosition,1);
        float2 oldUV=previous.xy/previous.w*0.5+0.5;
        bool valid=frame.config.w>0.5 && previous.w>0 && all(oldUV>0) && all(oldUV<1)
            && previous.z/previous.w>0 && previous.z/previous.w<1;
        if(valid) {
            uint2 oldPixel=min(uint2(oldUV*frame.config.xy),size-1);
            float expected=length(previousPosition),oldDistance=history.read(oldPixel).w;
            valid=isfinite(oldDistance) && abs(oldDistance-expected)<0.03+0.002*expected;
        }
        if(valid) {
            constexpr sampler linearSampler(coord::normalized,address::clamp_to_edge,filter::linear);
            float3 old=history.sample(linearSampler,oldUV).rgb;
            half3 low=half3(visible),high=half3(visible);
            for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                uint2 at=sunMaskNeighbour(pixel,int2(x,y),size,current);
                half3 value=current.read(at).rgb;low=min(low,value);high=max(high,value);
            }
            // A shadow-only history needs a soft neighbourhood limit: a hard color-style clamp
            // reproduces the raster pop when a whole filtered strip changes at once. Surface/depth
            // rejection and geometry revisions already discard newly exposed or modified surfaces.
            old=clamp(old,0.0,1.0);
            float motion=length((uv-oldUV)*frame.config.xy);
            float3 nearClip=(sun.clip*float4(position,1)).xyz;
            float coverage=max(abs(nearClip.x),abs(nearClip.y));
            float distant=smoothstep(0.55,0.97,coverage);
            // Reprojection tracks the surface; retain useful far history during camera motion.
            float alpha=max(frame.config.z,min(mix(1.0,0.3,distant),motion*0.04));
            old=mix(old,clamp(old,float3(low),float3(high)),min(0.05,alpha*0.25));
            float3 difference=abs(old-visible);
            float reactive=smoothstep(0.12,0.45,max(difference.x,max(difference.y,difference.z)));
            if((sun.enabled&2u)!=0) {
                // Coarse raster coverage changes are not proof of a moving entity.
                // Keep fast close-entity response while limiting global reactivity in the distance.
                alpha=max(alpha,mix(0.8,0.12,distant)*reactive);
            }
            // Rain and storms sway foliage harder (OwnWind); its shadow moves every frame. Follow the moving edges
            // instead of averaging them soft; still shadows (small differences) keep their smoothing.
            alpha=max(alpha,mix(0.6,0.12,distant)*reactive*sunWindGust(sun));
            if(all(isfinite(old))) visible=mix(old,visible,alpha);
        }
        color.rgb=sunTerrainColor(color.rgb,visible,mask.w,pixel,position,sun,fog,lighting,gAlbedo,gLight,gNormal,colored);
    } else color.rgb=sunSkyPixel(color.rgb,uv,depth.read(pixel),frame,sun,fog,lighting,auroraNoise,auroraLow);
    return {color,float4(visible,distance)};
}

// Frozen sun.16.3 filters for live A/B comparison; receiver culling is shared.
fragment float4 sun_deferred_mask_reference_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant SunBlendFrame &blend [[buffer(2)]],constant SunGlassFrame &glass [[buffer(3)]],
    texture2d<float> normals [[texture(0)]],depth2d<float> depth [[texture(1)]],
    array<depth2d<float>,4> shadows [[texture(20)]],array<depth2d<float>,4> glassDepth [[texture(24)]],
    array<texture2d<float>,4> glassColor [[texture(28)]],sampler shadowSampler [[sampler(13)]]) {
    uint2 pixel=uint2(in.position.xy);float4 surface=normals.read(pixel);
    if(sun.sun.w<=0)return float4(1,1,1,surface.w);
    float2 uv=(float2(pixel)+.5)/frame.config.xy;
    float4 point=frame.inverseView*float4(uv*2-1,depth.read(pixel),1);
    float3 normal=surface.xyz*rsqrt(max(dot(surface.xyz,surface.xyz),1e-12));
    // Background values are never applied by the subsequent resolve.
    float3 visible=sunSurfaceLighting(point.xyz/max(point.w,1e-8),normal,in.position.xy,sun,blend,glass,
        shadows,glassDepth,glassColor,shadowSampler,frame.config.z<1);
    return float4(surface.w>=0?visible:float3(1),surface.w);
}

fragment SunHistoryOutput sun_temporal_reference_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant Fog &fog [[buffer(24)]],constant SunLighting &lighting [[buffer(4)]],texture2d<float> gAlbedo [[texture(5)]],texture2d<float> gLight [[texture(6)]],texture2d<float> gNormal [[texture(7)]],texture2d<float> scene [[texture(0)]],texture2d<float> current [[texture(1)]],
    texture2d<float> history [[texture(2)]],depth2d<float> depth [[texture(3)]],texture2d<float> auroraNoise [[texture(32)]],device const uint *colored [[buffer(28)]],texture2d<float> auroraLow [[texture(33)]]) {
    uint2 pixel=uint2(in.position.xy),size=uint2(frame.config.xy);
    float2 uv=(float2(pixel)+0.5)/frame.config.xy;
    float4 mask=current.read(pixel),color=scene.read(pixel);
    float3 visible=mask.rgb;float distance=0;
    if(mask.w>=0) {
        float z=depth.read(pixel);
        float4 point=frame.inverseView*float4(uv*2.0-1.0,z,1);
        float3 position=point.xyz/point.w;distance=length(position);
        float3 previousPosition=position+frame.cameraDelta.xyz;
        float4 previous=frame.previousView*float4(previousPosition,1);
        float2 oldUV=previous.xy/previous.w*0.5+0.5;
        bool valid=frame.config.w>0.5 && previous.w>0 && all(oldUV>0) && all(oldUV<1)
            && previous.z/previous.w>0 && previous.z/previous.w<1;
        if(valid) {
            uint2 oldPixel=min(uint2(oldUV*frame.config.xy),size-1);
            float expected=length(previousPosition),oldDistance=history.read(oldPixel).w;
            valid=isfinite(oldDistance) && abs(oldDistance-expected)<0.03+0.002*expected;
        }
        if(valid) {
            constexpr sampler linearSampler(coord::normalized,address::clamp_to_edge,filter::linear);
            float3 old=history.sample(linearSampler,oldUV).rgb;
            float3 low=visible,high=visible;
            for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                uint2 at=uint2(clamp(int2(pixel)+int2(x,y),int2(0),int2(size)-1));
                float3 value=current.read(at).rgb;low=min(low,value);high=max(high,value);
            }
            // A shadow-only history needs a soft neighbourhood limit: a hard color-style clamp
            // reproduces the raster pop when a whole filtered strip changes at once. Surface/depth
            // rejection and geometry revisions already discard newly exposed or modified surfaces.
            old=clamp(old,0.0,1.0);
            float motion=length((uv-oldUV)*frame.config.xy);
            float3 nearClip=(sun.clip*float4(position,1)).xyz;
            float coverage=max(abs(nearClip.x),abs(nearClip.y));
            float distant=smoothstep(0.55,0.97,coverage);
            // Reprojection tracks the surface; retain useful far history during camera motion.
            float alpha=max(frame.config.z,min(mix(1.0,0.3,distant),motion*0.04));
            old=mix(old,clamp(old,low,high),min(0.05,alpha*0.25));
            float3 difference=abs(old-visible);
            float reactive=smoothstep(0.12,0.45,max(difference.x,max(difference.y,difference.z)));
            if((sun.enabled&2u)!=0) {
                // Coarse raster coverage changes are not proof of a moving entity.
                // Keep fast close-entity response while limiting global reactivity in the distance.
                alpha=max(alpha,mix(0.8,0.12,distant)*reactive);
            }
            if(all(isfinite(old))) visible=mix(old,visible,alpha);
        }
        color.rgb=sunTerrainColor(color.rgb,visible,mask.w,pixel,position,sun,fog,lighting,gAlbedo,gLight,gNormal,colored);
    } else color.rgb=sunSkyPixel(color.rgb,uv,depth.read(pixel),frame,sun,fog,lighting,auroraNoise,auroraLow);
    return {color,float4(visible,distance)};
}

// Two-stage caster selection: compact accepted sections, then share record work
// across one 32-lane SIMD group per section. No CPU readback or scene rescan.
kernel void sun_reset_select(device uint *args [[buffer(3)]],uint i [[thread_position_in_grid]]) {
    if(i<4)args[i]=i==0?384u:0u;
    if(i>=8 && i<12)args[i]=(i==9||i==10)?1u:0u;
    if(i>=12 && i<16)args[i]=i==12?384u:0u;
}
kernel void sun_reset_select_exact(device uint *args [[buffer(3)]],uint i [[thread_position_in_grid]]) {
    if(i<4)args[i]=i==1?1u:0u;
    if(i>=8 && i<12)args[i]=(i==9||i==10)?1u:0u;
    if(i>=12 && i<16)args[i]=i==13?1u:0u;
}
static bool sunSelectSection(constant SunFrame &f,device const Section *sections,constant SunCullFrame &casterCull,
    device const uint *bins,constant SunSurfaceCull &surface,device const uint *candidates,device atomic_uint *stats,thread uint &slot) {
    if(surface.candidateCount!=0xffffffffu){if(slot>=surface.candidateCount)return false;slot=candidates[slot];}
    if(slot>=f.sectionCount)return false;
    Section s=sections[slot];if(!s.recCount)return false;
    float3 origin=float3(int3(s.x,s.y,s.z)-f.camBlock.xyz)+f.camOffset.xyz;
    float3 center=(f.clip*float4(origin+8.0,1)).xyz;float margin=28.0/f.config.x;
    if(any(abs(center.xy)>1.0+margin)||center.z< -margin||center.z>1.0+margin)return false;
    if(!sunCasterVisible(casterCull,origin,origin+16.0))return false;
    if(sunStats(surface))atomic_fetch_add_explicit(stats,1u,memory_order_relaxed);
    if(!sunReceiverVisible(f,bins,surface,origin,origin+16.0)) {
        if(sunStats(surface))atomic_fetch_add_explicit(stats+1,1u,memory_order_relaxed);return false;
    }
    return true;
}
template <bool Exact>
static void sunCullShort(constant SunFrame &f,device const Rec *recs,device atomic_uint *args,device uint *list,
    constant SunCullFrame &casterCull,device const uint *surfaceBounds,constant SunSurfaceCull &surface,
    device atomic_uint *stats,Section s,float3 origin) {
    uint tested=0,rejected=0,emitted=0;
    for(uint at=s.recStart;at<s.recStart+s.recCount && at<f.listCapacity;at++) {
        if(((recs[at].meta>>9)&3)>1) continue;
        if(casterCull.count || surface.enabled) {
            uint lo=recs[at].lo,hi=recs[at].hi;
            float3 minimum=float3(int(lo&255)-16,int((lo>>8)&255)-16,int((lo>>16)&255)-16);
            float3 maximum=float3(int(hi&255)-16,int((hi>>8)&255)-16,int((hi>>16)&255)-16);
            if(!sunCasterVisible(casterCull,origin+minimum,origin+maximum))continue;
            tested++;
            if(!sunReceiverVisible(f,surfaceBounds,surface,origin+minimum,origin+maximum)){rejected++;continue;}
        }
        uint meta=recs[at].meta;emitted+=(meta&63u)+1u;
        sunEmit<Exact>(f,args,list,at,meta,sunCutoutSplit(surface,meta));
    }
    if(sunStats(surface)) {
        atomic_fetch_add_explicit(stats+2,tested,memory_order_relaxed);
        atomic_fetch_add_explicit(stats+3,rejected,memory_order_relaxed);
        atomic_fetch_add_explicit(stats+4,emitted,memory_order_relaxed);
    }
}

#define SUN_SELECT_PARAMS constant SunFrame &f [[buffer(0)]],device const Section *sections [[buffer(1)]], \
    device const Rec *recs [[buffer(2)]],device atomic_uint *args [[buffer(3)]],device uint *list [[buffer(4)]], \
    constant SunCullFrame &casterCull [[buffer(5)]],device const uint *bins [[buffer(6)]], \
    constant SunSurfaceCull &surface [[buffer(7)]],device const uint *candidates [[buffer(8)]], \
    device atomic_uint *stats [[buffer(9)]],device uint *selected [[buffer(10)]],uint slot [[thread_position_in_grid]], \
    uint lane [[thread_index_in_simdgroup]]
template <bool Exact>
static void sunSelect(constant SunFrame &f,device const Section *sections,device const Rec *recs,
    device atomic_uint *args,device uint *list,constant SunCullFrame &casterCull,device const uint *bins,
    constant SunSurfaceCull &surface,device const uint *candidates,device atomic_uint *stats,
    device uint *selected,uint slot,uint lane) {
    bool keep=sunSelectSection(f,sections,casterCull,bins,surface,candidates,stats,slot);
    if(keep) {
        Section s=sections[slot];
        // Short lists retain the cheap one-thread path, after the same coarse test.
        if(s.recCount<=8u) {
            float3 origin=float3(int3(s.x,s.y,s.z)-f.camBlock.xyz)+f.camOffset.xyz;
            sunCullShort<Exact>(f,recs,args,list,casterCull,bins,surface,stats,s,origin);keep=false;
        }
    }
    uint mine=keep?1u:0u,total=simd_sum(mine),before=simd_prefix_exclusive_sum(mine),base=0;
    if(!total)return;
    if(lane==0) {
        base=atomic_fetch_add_explicit(args+11,total,memory_order_relaxed);
        atomic_fetch_max_explicit(args+8,(base+total+1u)/2u,memory_order_relaxed);
    }
    base=simd_broadcast_first(base);
    if(keep)selected[base+before]=slot;
}

kernel void sun_select_sections(SUN_SELECT_PARAMS) { sunSelect<false>(f,sections,recs,args,list,casterCull,bins,surface,candidates,stats,selected,slot,lane); }
kernel void sun_select_sections_exact(SUN_SELECT_PARAMS) { sunSelect<true>(f,sections,recs,args,list,casterCull,bins,surface,candidates,stats,selected,slot,lane); }

template <bool Exact>
static void sunCullSelected(constant SunFrame &f,device const Section *sections,device const Rec *recs,
    device atomic_uint *args,device uint *list,constant SunCullFrame &casterCull,device const uint *bins,
    constant SunSurfaceCull &surface,device atomic_uint *stats,device const uint *selected,uint index,uint lane) {
    if(index>=atomic_load_explicit(args+11,memory_order_relaxed))return;
    uint slot=selected[index];Section s=sections[slot];
    float3 origin=float3(int3(s.x,s.y,s.z)-f.camBlock.xyz)+f.camOffset.xyz;
    uint end=min(s.recStart+s.recCount,f.listCapacity),tested=0,rejected=0,emitted=0;
    for(uint start=s.recStart;start<end;start+=32u) {
        uint at=start+lane,quads=0;bool keep=at<end,cutout=false;
        if(keep) {
            Rec r=recs[at];keep=((r.meta>>9)&3)<=1;
            if(keep && (casterCull.count || surface.enabled)) {
                float3 minimum=float3(int(r.lo&255)-16,int((r.lo>>8)&255)-16,int((r.lo>>16)&255)-16);
                float3 maximum=float3(int(r.hi&255)-16,int((r.hi>>8)&255)-16,int((r.hi>>16)&255)-16);
                keep=sunCasterVisible(casterCull,origin+minimum,origin+maximum);
                if(keep) {
                    tested++;
                    keep=sunReceiverVisible(f,bins,surface,origin+minimum,origin+maximum);
                    if(!keep)rejected++;
                }
            }
            if(keep){quads=(r.meta&63u)+1u;cutout=sunCutoutSplit(surface,r.meta);}
        }
        emitted+=quads;
        uint mine=Exact?quads:(keep?1u:0u),opaque=cutout?0u:mine,alpha=cutout?mine:0u;
        uint total=simd_sum(opaque),before=simd_prefix_exclusive_sum(opaque),base=0;
        if(total) {
            if(lane==0)base=Exact?atomic_fetch_add_explicit(args,total*6u,memory_order_relaxed)/6u:
                atomic_fetch_add_explicit(args+1,total,memory_order_relaxed);
            base=simd_broadcast_first(base);
            if(!cutout) {
                if(Exact){for(uint q=0;q<quads;q++)list[base+before+q]=(at<<6)|q;}
                else if(keep && base+before<f.listCapacity)list[base+before]=at;
            }
        }
        // Without the split no lane is a cutout and this block never runs.
        uint alphaTotal=simd_sum(alpha),alphaBefore=simd_prefix_exclusive_sum(alpha);base=0;
        if(alphaTotal) {
            if(lane==0)base=Exact?atomic_fetch_add_explicit(args+12,alphaTotal*6u,memory_order_relaxed)/6u:
                atomic_fetch_add_explicit(args+13,alphaTotal,memory_order_relaxed);
            base=simd_broadcast_first(base);
            if(cutout) {
                if(Exact){uint last=f.listCapacity*64u-1u;for(uint q=0;q<quads;q++)list[last-(base+alphaBefore+q)]=(at<<6)|q;}
                else if(base+alphaBefore<f.listCapacity)list[f.listCapacity-1u-(base+alphaBefore)]=at;
            }
        }
    }
    uint groupTested=simd_sum(tested),groupRejected=simd_sum(rejected),groupEmitted=simd_sum(emitted);
    if(sunStats(surface) && lane==0) {
        atomic_fetch_add_explicit(stats+2,groupTested,memory_order_relaxed);
        atomic_fetch_add_explicit(stats+3,groupRejected,memory_order_relaxed);
        atomic_fetch_add_explicit(stats+4,groupEmitted,memory_order_relaxed);
    }
}
kernel void sun_cull_selected(SUN_SELECT_PARAMS) { sunCullSelected<false>(f,sections,recs,args,list,casterCull,bins,surface,stats,selected,slot>>5,lane); }
kernel void sun_cull_selected_exact(SUN_SELECT_PARAMS) { sunCullSelected<true>(f,sections,recs,args,list,casterCull,bins,surface,stats,selected,slot>>5,lane); }
#undef SUN_SELECT_PARAMS

// Equivalent current-frame receiver scan with cooperative, monotonic cell updates.
static void sunMarkReceiverChecked(device atomic_uint *bins,uint level,float3 lo,float3 hi,float guard) {
    uint base=level*SUN_RECEIVER_STRIDE;
    if(!all(isfinite(lo))||!all(isfinite(hi))) {atomic_store_explicit(bins+base,1u,memory_order_relaxed);return;}
    lo.xy-=guard;hi.xy+=guard;
    // The comparison reference includes the clamped receiver-plane gradient.
    // Its depth excursion over the PCF footprint must be bounded as well.
    hi.z+=2*guard+.001;
    if(any(lo.xy>1)||any(hi.xy< -1)||hi.z<0||lo.z>1)return;
    int2 first=clamp(int2(floor((lo.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    int2 last=clamp(int2(floor((hi.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    uint farthest=as_type<uint>(clamp(hi.z,1e-8,1.0));
    for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++)
        if(atomic_load_explicit(bins+base+1+y*SUN_RECEIVER_SIDE+x,memory_order_relaxed)<farthest)
            atomic_fetch_max_explicit(bins+base+1+y*SUN_RECEIVER_SIDE+x,farthest,memory_order_relaxed);
}
static void sunMarkReceiverParallel(device atomic_uint *bins,uint level,float3 lo,float3 hi,float guard,uint rank,uint workers) {
    uint base=level*SUN_RECEIVER_STRIDE;
    if(!all(isfinite(lo))||!all(isfinite(hi))) {if(rank==0)atomic_store_explicit(bins+base,1u,memory_order_relaxed);return;}
    lo.xy-=guard;hi.xy+=guard;hi.z+=2*guard+.001;
    if(any(lo.xy>1)||any(hi.xy< -1)||hi.z<0||lo.z>1)return;
    int2 first=clamp(int2(floor((lo.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    int2 last=clamp(int2(floor((hi.xy*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
    int2 span=last-first+1;if(any(span<=0))return;
    uint farthest=as_type<uint>(clamp(hi.z,1e-8,1.0)),width=uint(span.x);
    // Power-of-two row addressing avoids variable integer divide/remainder per cell.
    uint shift=32u-clz(width-1u),stride=1u<<shift;
    uint count=stride*uint(span.y);
    // rank/workers use the actual active lanes, including a partial final SIMD group.
    for(uint i=rank;i<count;i+=workers) {
        uint x=i&(stride-1u),y=i>>shift;
        if(x>=width)continue;
        uint at=base+1+(uint(first.y)+y)*SUN_RECEIVER_SIDE+uint(first.x)+x;
        // Values grow monotonically during this pass. A value already >= ours needs no RMW.
        if(atomic_load_explicit(bins+at,memory_order_relaxed)<farthest)
            atomic_fetch_max_explicit(bins+at,farthest,memory_order_relaxed);
    }
}

static void sunReceiversOptimized(constant SunReceiverFrame &f,device atomic_uint *bins,
    depth2d<float,access::read> depth,texture2d<float,access::read> normals,uint2 pixel,uint lane) {
    bool valid=all(pixel<f.config.xy);float3 position=float3(0);uint bad=0;
    if(valid) {
        valid=normals.read(pixel).w>=0;
        if(valid) {
            float z=depth.read(pixel);float2 uv=(float2(pixel)+.5)/float2(f.config.xy);
            float4 p=f.inverse*float4(uv*2-1,z,1);
            if(!all(isfinite(p))||p.w<=1e-8||z<=0||z>1){bad=1;valid=false;}
            else position=p.xyz/p.w;
        }
    }
    bad=simd_max(bad);
    if(bad) {
        if(lane==0)for(uint level=0;level<f.config.z;level++)atomic_store_explicit(bins+level*SUN_RECEIVER_STRIDE,1u,memory_order_relaxed);
        return;
    }
    if(!simd_any(valid))return;
    uint rank=simd_prefix_exclusive_sum(1u),workers=simd_sum(1u);
    for(uint level=0;level<f.config.z;level++) {
        float infinity=as_type<float>(0x7f800000u);
        float3 q=(f.clips[level]*float4(position,1)).xyz;
        float3 lo=valid?q:float3(infinity),hi=valid?q:float3(-infinity);
        lo=float3(simd_min(lo.x),simd_min(lo.y),simd_min(lo.z));
        hi=float3(simd_max(hi.x),simd_max(hi.y),simd_max(hi.z));
        if(all(lo<=hi)) {
            if(!all(isfinite(lo))||!all(isfinite(hi))) {
                if(lane==0)atomic_store_explicit(bins+level*SUN_RECEIVER_STRIDE,1u,memory_order_relaxed);
            } else {
                float guard=f.guards[level];
                int2 first=clamp(int2(floor(((lo.xy-guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                int2 last=clamp(int2(floor(((hi.xy+guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                uint cells=uint(last.x-first.x+1)*uint(last.y-first.y+1);
                if(cells<=64) {
                    sunMarkReceiverParallel(bins,level,lo,hi,guard,rank,workers);
                } else {
                    // Separate opposite sides of a large near/far discontinuity.
                    // Both subsets stay conservative, but do not fill their empty gap.
                    uint axis=hi.x-lo.x>hi.y-lo.y?0u:1u;
                    float middle=(lo[axis]+hi[axis])*.5;
                    for(uint subset=0;subset<2;subset++) {
                        bool member=valid && uint(q[axis]>middle)==subset;
                        float3 groupLo=member?q:float3(infinity),groupHi=member?q:float3(-infinity);
                        groupLo=float3(simd_min(groupLo.x),simd_min(groupLo.y),simd_min(groupLo.z));
                        groupHi=float3(simd_max(groupHi.x),simd_max(groupHi.y),simd_max(groupHi.z));
                        if(all(groupLo<=groupHi)) {
                            int2 a=clamp(int2(floor(((groupLo.xy-guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                            int2 b=clamp(int2(floor(((groupHi.xy+guard)*.5+.5)*SUN_RECEIVER_SIDE)),int2(0),int2(SUN_RECEIVER_SIDE-1));
                            uint area=uint(b.x-a.x+1)*uint(b.y-a.y+1);
                            if(area<=64) {
                                sunMarkReceiverParallel(bins,level,groupLo,groupHi,guard,rank,workers);
                            } else if(member) {
                                // Bound remaining outliers by their own PCF footprint.
                                // At supported sizes that footprint is at most 5x5 cells.
                                sunMarkReceiverChecked(bins,level,q,q,guard);
                            }
                        }
                    }
                }
            }
        }
    }

}

kernel void sun_receivers_mark_optimized(constant SunReceiverFrame &f [[buffer(0)]],device atomic_uint *bins [[buffer(1)]],
    depth2d<float,access::read> depth [[texture(0)]],texture2d<float,access::read> normals [[texture(1)]],
    uint2 pixel [[thread_position_in_grid]],uint lane [[thread_index_in_simdgroup]]) {
    sunReceiversOptimized(f,bins,depth,normals,pixel,lane);
}

// Same filter math, with the current pixel supplied by the color attachment.
fragment SunHistoryOutput sun_temporal_fetch_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant Fog &fog [[buffer(24)]],constant SunLighting &lighting [[buffer(4)]],texture2d<float> gAlbedo [[texture(5)]],texture2d<float> gLight [[texture(6)]],texture2d<float> gNormal [[texture(7)]],float4 sceneColor [[color(0)]],texture2d<half> current [[texture(1)]],
    texture2d<float> history [[texture(2)]],depth2d<float> depth [[texture(3)]],texture2d<float> surfaces [[texture(8)]],texture2d<float> auroraNoise [[texture(32)]],device const uint *colored [[buffer(28)]],texture2d<float> auroraLow [[texture(33)]]) {
    uint2 pixel=uint2(in.position.xy),size=uint2(frame.config.xy);
    float2 uv=(float2(pixel)+0.5)/frame.config.xy;
    float4 mask=SUN_HALF?sunHalfMask(current,surfaces,pixel):float4(current.read(pixel)),color=sceneColor;
    float3 visible=mask.rgb;float distance=0;
    if(mask.w>=0) {
        float z=depth.read(pixel);
        float4 point=frame.inverseView*float4(uv*2.0-1.0,z,1);
        float3 position=point.xyz/point.w;distance=length(position);
        float3 previousPosition=position+frame.cameraDelta.xyz;
        float4 previous=frame.previousView*float4(previousPosition,1);
        float2 oldUV=previous.xy/previous.w*0.5+0.5;
        bool valid=frame.config.w>0.5 && previous.w>0 && all(oldUV>0) && all(oldUV<1)
            && previous.z/previous.w>0 && previous.z/previous.w<1;
        if(valid) {
            uint2 oldPixel=min(uint2(oldUV*frame.config.xy),size-1);
            float expected=length(previousPosition),oldDistance=history.read(oldPixel).w;
            valid=isfinite(oldDistance) && abs(oldDistance-expected)<0.03+0.002*expected;
        }
        if(valid) {
            constexpr sampler linearSampler(coord::normalized,address::clamp_to_edge,filter::linear);
            float3 old=history.sample(linearSampler,oldUV).rgb;
            half3 low=half3(visible),high=half3(visible);
            for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                uint2 at=sunMaskNeighbour(pixel,int2(x,y),size,current);
                half3 value=current.read(at).rgb;low=min(low,value);high=max(high,value);
            }
            // A shadow-only history needs a soft neighbourhood limit: a hard color-style clamp
            // reproduces the raster pop when a whole filtered strip changes at once. Surface/depth
            // rejection and geometry revisions already discard newly exposed or modified surfaces.
            old=clamp(old,0.0,1.0);
            float motion=length((uv-oldUV)*frame.config.xy);
            float3 nearClip=(sun.clip*float4(position,1)).xyz;
            float coverage=max(abs(nearClip.x),abs(nearClip.y));
            float distant=smoothstep(0.55,0.97,coverage);
            // Reprojection tracks the surface; retain useful far history during camera motion.
            float alpha=max(frame.config.z,min(mix(1.0,0.3,distant),motion*0.04));
            old=mix(old,clamp(old,float3(low),float3(high)),min(0.05,alpha*0.25));
            float3 difference=abs(old-visible);
            float reactive=smoothstep(0.12,0.45,max(difference.x,max(difference.y,difference.z)));
            if((sun.enabled&2u)!=0) {
                // Coarse raster coverage changes are not proof of a moving entity.
                // Keep fast close-entity response while limiting global reactivity in the distance.
                alpha=max(alpha,mix(0.8,0.12,distant)*reactive);
            }
            // Rain and storms sway foliage harder (OwnWind); its shadow moves every frame. Follow the moving edges
            // instead of averaging them soft; still shadows (small differences) keep their smoothing.
            alpha=max(alpha,mix(0.6,0.12,distant)*reactive*sunWindGust(sun));
            if(all(isfinite(old))) visible=mix(old,visible,alpha);
        }
        color.rgb=sunTerrainColor(color.rgb,visible,mask.w,pixel,position,sun,fog,lighting,gAlbedo,gLight,gNormal,colored);
    } else color.rgb=sunSkyPixel(color.rgb,uv,depth.read(pixel),frame,sun,fog,lighting,auroraNoise,auroraLow);
    return {color,float4(visible,distance)};
}

fragment SunHistoryOutput sun_temporal_reference_fetch_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant Fog &fog [[buffer(24)]],constant SunLighting &lighting [[buffer(4)]],texture2d<float> gAlbedo [[texture(5)]],texture2d<float> gLight [[texture(6)]],texture2d<float> gNormal [[texture(7)]],float4 sceneColor [[color(0)]],texture2d<float> current [[texture(1)]],
    texture2d<float> history [[texture(2)]],depth2d<float> depth [[texture(3)]],texture2d<float> auroraNoise [[texture(32)]],device const uint *colored [[buffer(28)]],texture2d<float> auroraLow [[texture(33)]]) {
    uint2 pixel=uint2(in.position.xy),size=uint2(frame.config.xy);
    float2 uv=(float2(pixel)+0.5)/frame.config.xy;
    float4 mask=current.read(pixel),color=sceneColor;
    float3 visible=mask.rgb;float distance=0;
    if(mask.w>=0) {
        float z=depth.read(pixel);
        float4 point=frame.inverseView*float4(uv*2.0-1.0,z,1);
        float3 position=point.xyz/point.w;distance=length(position);
        float3 previousPosition=position+frame.cameraDelta.xyz;
        float4 previous=frame.previousView*float4(previousPosition,1);
        float2 oldUV=previous.xy/previous.w*0.5+0.5;
        bool valid=frame.config.w>0.5 && previous.w>0 && all(oldUV>0) && all(oldUV<1)
            && previous.z/previous.w>0 && previous.z/previous.w<1;
        if(valid) {
            uint2 oldPixel=min(uint2(oldUV*frame.config.xy),size-1);
            float expected=length(previousPosition),oldDistance=history.read(oldPixel).w;
            valid=isfinite(oldDistance) && abs(oldDistance-expected)<0.03+0.002*expected;
        }
        if(valid) {
            constexpr sampler linearSampler(coord::normalized,address::clamp_to_edge,filter::linear);
            float3 old=history.sample(linearSampler,oldUV).rgb;
            float3 low=visible,high=visible;
            for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                uint2 at=uint2(clamp(int2(pixel)+int2(x,y),int2(0),int2(size)-1));
                float3 value=current.read(at).rgb;low=min(low,value);high=max(high,value);
            }
            // A shadow-only history needs a soft neighbourhood limit: a hard color-style clamp
            // reproduces the raster pop when a whole filtered strip changes at once. Surface/depth
            // rejection and geometry revisions already discard newly exposed or modified surfaces.
            old=clamp(old,0.0,1.0);
            float motion=length((uv-oldUV)*frame.config.xy);
            float3 nearClip=(sun.clip*float4(position,1)).xyz;
            float coverage=max(abs(nearClip.x),abs(nearClip.y));
            float distant=smoothstep(0.55,0.97,coverage);
            // Reprojection tracks the surface; retain useful far history during camera motion.
            float alpha=max(frame.config.z,min(mix(1.0,0.3,distant),motion*0.04));
            old=mix(old,clamp(old,low,high),min(0.05,alpha*0.25));
            float3 difference=abs(old-visible);
            float reactive=smoothstep(0.12,0.45,max(difference.x,max(difference.y,difference.z)));
            if((sun.enabled&2u)!=0) {
                // Coarse raster coverage changes are not proof of a moving entity.
                // Keep fast close-entity response while limiting global reactivity in the distance.
                alpha=max(alpha,mix(0.8,0.12,distant)*reactive);
            }
            if(all(isfinite(old))) visible=mix(old,visible,alpha);
        }
        color.rgb=sunTerrainColor(color.rgb,visible,mask.w,pixel,position,sun,fog,lighting,gAlbedo,gLight,gNormal,colored);
    } else color.rgb=sunSkyPixel(color.rgb,uv,depth.read(pixel),frame,sun,fog,lighting,auroraNoise,auroraLow);
    return {color,float4(visible,distance)};
}

fragment SunHistoryOutput sun_deferred_apply_fetch_fs(SunScreenVertex in [[stage_in]],constant SunTemporalFrame &frame [[buffer(0)]],
    constant SunFrame &sun [[buffer(1)]],constant SunBlendFrame &blend [[buffer(2)]],constant SunGlassFrame &glass [[buffer(3)]],
    constant Fog &fog [[buffer(24)]],constant SunLighting &lighting [[buffer(4)]],texture2d<float> gAlbedo [[texture(5)]],texture2d<float> gLight [[texture(6)]],texture2d<float> gNormal [[texture(7)]],texture2d<float> normals [[texture(0)]],depth2d<float> depth [[texture(1)]],float4 sceneColor [[color(0)]],
    array<depth2d<float>,4> shadows [[texture(20)]],array<depth2d<float>,4> glassDepth [[texture(24)]],
    array<texture2d<float>,4> glassColor [[texture(28)]],sampler shadowSampler [[sampler(13)]],texture2d<float> auroraNoise [[texture(32)]],device const uint *colored [[buffer(28)]],texture2d<float> auroraLow [[texture(33)]]) {
    float3 position;float4 mask=sunCurrentMask(in.position.xy,frame,sun,blend,glass,normals,depth,shadows,glassDepth,glassColor,shadowSampler,position);
    // Preserve the original RGBA16Float intermediate's quantization exactly.
    float3 visible=float3(half3(mask.rgb));float4 color=sceneColor;float distance=0;
    if(mask.w>=0){distance=length(position);color.rgb=sunTerrainColor(color.rgb,visible,mask.w,uint2(in.position.xy),position,sun,fog,lighting,gAlbedo,gLight,gNormal,colored);}
    else color.rgb=sunSkyPixel(color.rgb,in.position.xy/frame.config.xy,depth.read(uint2(in.position.xy)),frame,sun,fog,lighting,auroraNoise,auroraLow);
    SunHistoryOutput result;result.color=color;result.history=float4(visible,distance);return result;
}

// Native underwater lens/fog after the world, before vanilla post effects and HUD.
fragment float4 own_water_post_fs(SunScreenVertex in [[stage_in]],constant WaterFrame &f [[buffer(0)]],
    texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/f.screen.xy;
    bool inside=f.state.y>.5;float wet=saturate(f.state.z),time=f.state.x/4096.0,turn=6.28318530718;
    // quality.y (Shaders → Water → Underwater quality): 1 one caustic layer, 2 no caustics and no wave lens.
    int quality=f.quality.y>1.5?2:f.quality.y>.5?1:0;float2 displaced=uv;
    if(quality<2) {
        float2 lens=float2(sin(turn*fract(uv.y*19/turn+time*782))+.4*sin(turn*fract(uv.x*31/turn-time*522)),cos(turn*fract(uv.x*17/turn+time*587)));
        float edge=smoothstep(0.0,.08,min(min(uv.x,uv.y),min(1-uv.x,1-uv.y)));
        displaced=clamp(uv+lens*(inside?.0018:.0009)*wet*edge*f.effects.x,float2(.001),float2(.999));
        float2 center=uv-.5;float radius=length(center);
        displaced=clamp(displaced+center/max(radius,.01)*sin(radius*36-turn*fract(time*5215))*f.controls.z*.001*edge*f.effects.x,float2(.001),float2(.999));
    }
    float4 color=scene.sample(linear,displaced,level(0));float3 rgb=waterLinear(color.rgb,f);
    if(inside&&f.state.w>.5) {
        uint2 pixel=min(uint2(displaced*f.screen.xy),uint2(f.screen.xy)-1);float3 position=float3(0);
        bool valid=waterWorld(displaced,depth.read(pixel),f,position);
        // Sky/missing depth gets a stable far-water path, never a value derived
        // from vanilla's changing water-vision distance.
        float distance=valid?length(position):max(f.optics.z,1.0)*3;
        float opticalDepth=pow(distance/max(f.optics.z,1.0),2.0)*f.optics.y;
        float3 transmittance=exp(-opticalDepth*float3(1.35,.80,.55));
        float3 normal=cross(dfdx(position),dfdy(position));normal*=rsqrt(max(dot(normal,normal),1e-10));
        float daylight=smoothstep(-.08,.15,f.sun.y);
        float3 fog=mix(float3(.012,.045,.055),pow(max(f.fog.rgb,.005),2.2),.35)*(.12+.88*daylight);
        float3 direction=valid?normalize(position):float3(0,1,0);
        float shaft=pow(saturate(dot(direction,f.sun.xyz)),12.0)*f.sun.w;
        fog+=float3(.035,.045,.035)*shaft;
        if(valid&&quality<2&&f.optics.w>0&&f.sun.w>0) {
            if(dot(normal,-position)<0)normal=-normal;
            if(normal.y>0)rgb*=1+.22*f.optics.w*waterCaustic(position,normal.y,f,quality?1:2)*exp(-distance*.015);
        }
        rgb=rgb*transmittance+fog*(1-transmittance);
    }
    float vignette=saturate(length(uv-.5)*1.1);
    rgb*=1-(inside?.06:.025)*wet*vignette*vignette;
    color.rgb=waterDisplay(rgb,f);return color;
}

// Nearly the same picture at about half the cost (WaterFrame quality.w, Water > Underwater half resolution; 1.48 to 0.67 ms at
// 1920x1200 on an M1 Pro, underwater_half_check): fog and caustics in a half-size pass. Color 0 is what the scene is multiplied by (transmittance and caustics), color 1 the fog scattered in, with the depth
// that sample stands for. Only while fully submerged; the lens and vignette stay full size in the pass below.
struct WaterUnderLow { float4 multiply [[color(0)]];float4 inscatter [[color(1)]]; };
fragment WaterUnderLow own_water_low_fs(SunScreenVertex in [[stage_in]],constant WaterFrame &f [[buffer(0)]],depth2d<float> depth [[texture(1)]]) {
    float2 low=floor((f.screen.xy+1.0)*.5),uv=in.position.xy/low;
    uint2 pixel=min(uint2(uv*f.screen.xy),uint2(f.screen.xy)-1);float z=depth.read(pixel);
    int quality=f.quality.y>1.5?2:f.quality.y>.5?1:0;float3 position=float3(0);
    bool valid=waterWorld(uv,z,f,position);
    float distance=valid?length(position):max(f.optics.z,1.0)*3;
    float opticalDepth=pow(distance/max(f.optics.z,1.0),2.0)*f.optics.y;
    float3 transmittance=exp(-opticalDepth*float3(1.35,.80,.55));
    float3 normal=cross(dfdx(position),dfdy(position));normal*=rsqrt(max(dot(normal,normal),1e-10));
    float daylight=smoothstep(-.08,.15,f.sun.y);
    float3 fog=mix(float3(.012,.045,.055),pow(max(f.fog.rgb,.005),2.2),.35)*(.12+.88*daylight);
    float3 direction=valid?normalize(position):float3(0,1,0);
    fog+=float3(.035,.045,.035)*pow(saturate(dot(direction,f.sun.xyz)),12.0)*f.sun.w;
    float caustic=1.0;
    if(valid&&quality<2&&f.optics.w>0&&f.sun.w>0) {
        if(dot(normal,-position)<0)normal=-normal;
        if(normal.y>0)caustic=1+.22*f.optics.w*waterCaustic(position,normal.y,f,quality?1:2)*exp(-distance*.015);
    }
    WaterUnderLow out;out.multiply=float4(transmittance*caustic,1);out.inscatter=float4(fog*(1-transmittance),z);return out;
}
fragment float4 own_water_post_half_fs(SunScreenVertex in [[stage_in]],constant WaterFrame &f [[buffer(0)]],
    texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]],texture2d<float> lowMultiply [[texture(2)]],texture2d<float> lowInscatter [[texture(3)]]) {
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    float2 uv=in.position.xy/f.screen.xy;
    float wet=saturate(f.state.z),time=f.state.x/4096.0,turn=6.28318530718;
    int quality=f.quality.y>1.5?2:f.quality.y>.5?1:0;float2 displaced=uv;
    if(quality<2) {
        float2 lens=float2(sin(turn*fract(uv.y*19/turn+time*782))+.4*sin(turn*fract(uv.x*31/turn-time*522)),cos(turn*fract(uv.x*17/turn+time*587)));
        float edge=smoothstep(0.0,.08,min(min(uv.x,uv.y),min(1-uv.x,1-uv.y)));
        displaced=clamp(uv+lens*.0018*wet*edge*f.effects.x,float2(.001),float2(.999));
        float2 center=uv-.5;float radius=length(center);
        displaced=clamp(displaced+center/max(radius,.01)*sin(radius*36-turn*fract(time*5215))*f.controls.z*.001*edge*f.effects.x,float2(.001),float2(.999));
    }
    float4 color=scene.sample(linear,displaced,level(0));float3 rgb=waterLinear(color.rgb,f);
    uint2 pixel=min(uint2(displaced*f.screen.xy),uint2(f.screen.xy)-1);float z=depth.read(pixel);
    float2 low=floor((f.screen.xy+1.0)*.5),lowUv=displaced*f.screen.xy*.5/low;
    // Reversed depth: the relative difference is the relative difference in distance. Plain filtering wherever the four
    // half-size samples stand for the same surface; across an edge only the samples from this pixel's own side count.
    float4 depths=lowInscatter.gather(linear,lowUv,int2(0),component::w);
    float4 apart=abs(depths-z)/max(max(depths,float4(z)),float4(1e-6));
    float3 multiply,inscatter;
    if(all(apart<.03)) {
        multiply=lowMultiply.sample(linear,lowUv,level(0)).rgb;inscatter=lowInscatter.sample(linear,lowUv,level(0)).rgb;
    } else {
        float2 at=lowUv*low-.5,base=floor(at),t=at-base;
        // gather's order: (0,1), (1,1), (1,0), (0,0).
        float4 weight=float4((1-t.x)*t.y,t.x*t.y,t.x*(1-t.y),(1-t.x)*(1-t.y))*exp(-apart*40.0)+1e-5;
        const int2 offsets[4]={int2(0,1),int2(1,1),int2(1,0),int2(0,0)};
        multiply=float3(0);inscatter=float3(0);
        for(int i=0;i<4;i++) {
            uint2 texel=uint2(clamp(int2(base)+offsets[i],int2(0),int2(low)-1));
            multiply+=lowMultiply.read(texel).rgb*weight[i];inscatter+=lowInscatter.read(texel).rgb*weight[i];
        }
        float total=weight.x+weight.y+weight.z+weight.w;multiply/=total;inscatter/=total;
    }
    rgb=rgb*multiply+inscatter;
    float vignette=saturate(length(uv-.5)*1.1);
    rgb*=1-.06*wet*vignette*vignette;
    color.rgb=waterDisplay(rgb,f);return color;
}

// Transparent surface optics in the existing sorted forward draw. Own implementation inspired by the
// reference's material distinctions, angle dependence and near-camera fade; no copied GLSL or screen-space trace.
static float4 ownTranslucentShade(float4 texel,uint kind,float3 p,float3 n,float2 light,float3 visible,constant SunFrame &sun,constant SunLighting &l,device const uint *colored) {
    float3 eyeDelta=float3(l.pad0,l.pad1,l.hand[2].w)-p;float distance=max(length(eyeDelta),1e-4);float3 v=eyeDelta/distance;
    float nv=saturate(dot(n,v)),edge=1-nv,edge2=edge*edge;
    float fresnel=.04+.96*edge2*edge2*edge;
    float close=smoothstep(.35,1.8,distance),opacity=saturate(texel.a),sky=saturate((light.y*16-.5)/15),block=saturate((light.x*16-.5)/15);
    float3 tint=max(texel.rgb,0.0);float rough=kind==1?.18:kind==2?.38:kind==3?.2:kind==4?.28:kind==5?.37:.31;
    float specular=kind<=3?.8:kind==4?.65:.55;
    if(kind==1){if(opacity<.001){tint=float3(.78,.83,.88);opacity=.018;} }
    if(kind==3)opacity=max(opacity,.35);
    if(kind==4)rough=mix(.45,.18,saturate(tint.g));
    if(kind>=5){float smooth=kind==5?tint.g:tint.r;rough=mix(.6,.23,saturate(smooth));}
    opacity*=mix(.65,1.0,smoothstep(.5,4.0,distance));opacity=saturate(opacity*l.materials[3].y);
    rough=clamp(rough*l.materials[0].z,.16,1.0);
    float3 albedo=pow(tint,2.2),lit=sunTerrainLight(albedo,block,sky,n,visible,p,sun,l,colored);
    float nl=saturate(dot(n,sun.sun.xyz)),direct=sun.sun.w*smoothstep(.05,.6,sky);
    float3 h=v+sun.sun.xyz;h*=rsqrt(max(dot(h,h),1e-8));
    float nh=saturate(dot(n,h)),a=rough*rough,a2=a*a,d=nh*nh*(a2-1)+1,k=(rough+1)*(rough+1)*.125;
    float geometry=nv/max(nv*(1-k)+k,.001)*nl/max(nl*(1-k)+k,.001);
    float3 radiance=sunDirectColor(sun,l);
    float3 highlight=a2/(3.14159265*d*d)*geometry*.04/max(4*nv,.08)*radiance*visible*direct*specular*l.materials[0].y;
    highlight=highlight/(1+highlight*.25);
    float3 ray=reflect(-v,n);
    // Roughness blends the reflected sky direction toward the normal; skylight keeps cave surfaces from reflecting an outdoor sky.
    float3 reflectedSky=sunSkyLinear(normalize(mix(ray,n,rough*rough*.65)),sun,l)*sky*sky;
    float reflection=saturate(fresnel*close*l.materials[3].z)*(kind==4?.7:kind==5?.7:1.0);
    float alpha=saturate(opacity+reflection*(1-opacity));
    float3 weighted=lit*opacity*(1-reflection)+reflectedSky*reflection+highlight*opacity;
    // Return straight-alpha color for the renderer's existing RGB blend, honoring its existing source-over alpha and sorting.
    float3 rgb=weighted/max(alpha,.001);rgb=sunDisplay(rgb,l);
    return float4(l.srgb?pow(rgb,2.2):rgb,alpha);
}

// Screen-visible world reflections on opaque CPU-tagged surfaces. Uses the tested water trace in the same camera space.
// Transparent objects are drawn afterwards; no recursive reflection feedback, history or additional material attachment.
static float3 surfaceReflectionOptics(float3 texel,uint id,constant SunLighting &l) {
    if(id==0||id>=SURFACE_PROFILE_COUNT||l.materials[0].x<.5)return float3(1,0,0);
    SurfaceProfile m=SURFACE_PROFILES[id];if(m.detail.w<=0)return float3(1,0,0);
    uint group=uint(m.optical.w);float on=group==1?l.materials[1].y:group==2?l.materials[1].z:group==5?l.materials[2].y:0;
    if(on<.5)return float3(1,0,0);
    float hi=max(texel.r,max(texel.g,texel.b)),lo=min(texel.r,min(texel.g,texel.b)),mask=1;
    uint mode=uint(m.detail.x);float metal=float(m.optical.y),gloss=float(m.optical.z);
    if(mode>=3&&mode<=9){
        mask=smoothstep(.035,.16,hi-lo);
        if(mode==3)mask*=smoothstep(.015,.13,texel.r-texel.b);
        if(mode==4)mask*=smoothstep(.06,.25,texel.g-texel.b);
        if(mode==5)mask*=smoothstep(.025,.15,max(texel.r,texel.g)-texel.b);
        if(mode==6)mask*=smoothstep(.025,.15,min(texel.g,texel.b)-texel.r);
        if(mode==7)mask*=smoothstep(.04,.2,texel.g-max(texel.r,texel.b));
        if(mode==8)mask*=smoothstep(.04,.18,texel.b-texel.r);
        if(mode==9)mask*=smoothstep(.04,.2,texel.r-max(texel.g,texel.b));
        metal*=mask;gloss*=mask;
    }else if(mode==1)metal*=mix(.2,1.0,smoothstep(-.15,.2,texel.r-texel.g));
    else if(mode==2){mask=max(1-smoothstep(.08,.2,abs(texel.r-texel.b)),smoothstep(.12,.35,texel.g-texel.b));metal*=mask;gloss*=mask;}
    float rough=clamp(float(m.optical.x)*l.materials[0].z*(1.12-.24*dot(texel,float3(.2126,.7152,.0722))),.16,1.0);
    if(l.p[9].x>.5&&l.p[9].y>0){float response=sunSurfaceTextureResponse(saturate(texel),uint(m.detail.w)),strength=clamp(l.p[9].y,0.0,2.0);rough=clamp(rough*max(.15,1+(mix(1.55,.65,response)-1)*strength),.16,1.0);gloss*=max(0.0,1+(mix(.2,1.8,response)-1)*strength);}
    return float3(rough,gloss,metal);
}
// A half/quarter-size trace also feeds neighboring full-size pixels. Cull only if every possible
// consumer of this material/normal is fully obscured; otherwise keep the original trace unchanged.
static bool surfaceReflectionHidden(uint2 source,uint id,float4 normal,float3 p,constant WaterFrame &f,constant SunLighting &l,constant Fog &fog,
    depth2d<float> depth,texture2d<float> data,texture2d<float> normals) {
    uint2 representative=min(uint2((float2(source)+.5)/f.screen.zw*f.screen.xy),uint2(f.screen.xy)-1);
    if(sunFogTransmission(normal.w,p,data.read(representative).w,fog,l)>0)return false;
    float2 ratio=f.screen.xy/f.screen.zw;
    int2 low=max(int2(ceil((float2(source)-.5)*ratio-.5-.001)),int2(0));
    int2 high=min(int2(floor((float2(source)+1.5)*ratio-.5+.001)),int2(f.screen.xy)-1);
    for(int y=low.y;y<=high.y;y++)for(int x=low.x;x<=high.x;x++) {
        uint2 at=uint2(x,y);float4 n=normals.read(at);float4 d=data.read(at);
        if(n.w<0||uint(round(d.z*255))!=id||dot(normal.xyz,n.xyz)<.95)continue;
        if(n.w>=1||d.w<=0)continue;
        float3 q;if(!waterWorld((float2(at)+.5)/f.screen.xy,depth.read(at),f,q))return false;
        if(sunFogTransmission(n.w,q,d.w,fog,l)>0)return false;
    }
    return true;
}
// Receiver radius, not ray length: camera height counts as distance too. Existing frame slots keep the ABI unchanged.
static float surfaceRainFade(float distanceSquared,constant WaterFrame &f) {
    return f.state.z>0&&distanceSquared<f.state.w?1-smoothstep(f.state.w*.64,f.state.w,distanceSquared):0;
}
static float3 surfaceRainNormal(float3 n,SunWet wet,float fade) {
    return fade>=1?wet.normal:normalize(mix(n,wet.normal,fade));
}
static float3 surfaceMaterialReflectionWeight(float3 texel,float3 optics,float nv,float strength) {
    if(optics.y<=0||optics.x>.85)return float3(0);
    float hi=max(texel.r,max(texel.g,texel.b));float3 tint=mix(float3(1),texel/max(hi,.001),optics.z);
    float3 f0=mix(float3(.04),tint*.65,optics.z);float grazing=1-saturate(nv),g2=grazing*grazing;
    return (f0+(1-f0)*(g2*g2*grazing))*min(.85,optics.y*(1-optics.x)*(1-optics.x)*strength);
}
// Rain puddles (sunWetness) on any opaque surface trace too, replacing their sky reflection where the trace hits; the thin
// film elsewhere keeps the sky only (Complementary's Mac path), so only puddle pixels pay for the search.
fragment float4 surface_reflection_fs(SunScreenVertex in [[stage_in]],constant WaterFrame &f [[buffer(0)]],constant SunLighting &l [[buffer(1)]],constant Fog &fog [[buffer(2)]],constant SunFrame &sun [[buffer(3)]],
    texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]],texture2d<float> albedo [[texture(2)]],texture2d<float> data [[texture(3)]],texture2d<float> normals [[texture(4)]],texture2d<float> terrainHistory [[texture(5)]]) {
    float2 uv=in.position.xy/f.screen.zw;uint2 pixel=min(uint2(uv*f.screen.xy),uint2(f.screen.xy)-1);
    float4 normal=normals.read(pixel);if(normal.w<0)return float4(0);
    float4 light=data.read(pixel);uint id=uint(round(light.z*255));bool rain=l.p[18].z>0&&f.state.z>0;
    bool profiled=id>0&&id<SURFACE_PROFILE_COUNT&&SURFACE_PROFILES[id].detail.w>0;
    if(!profiled&&!rain)return float4(0);
    float3 texel=albedo.read(pixel).rgb,optics=profiled?surfaceReflectionOptics(texel,id,l):float3(1,0,0);
    bool material=optics.y>0&&optics.x<=.85;
    if(!material&&!rain)return float4(0);
    float terrainDistance=terrainHistory.read(pixel).w;
    if(!material&&terrainDistance>f.controls.x)return float4(0); // no reconstruction, wet noise or trace for far puddles
    float3 p;if(!waterWorld((float2(pixel)+.5)/f.screen.xy,depth.read(pixel),f,p))return float4(0);
    // The material G-buffer predates opaque entities. Its current-frame history already carries terrain distance;
    // reject a closer object instead of accidentally giving it the underlying floor's material. No depth snapshot needed.
    if(terrainDistance<=0||abs(length(p)-terrainDistance)>max(.02,terrainDistance*.0007))return float4(0);
    float3 n=normalize(normal.xyz),eyeDelta=waterEye(f)-p,v=eyeDelta*rsqrt(max(dot(eyeDelta,eyeDelta),1e-8));if(dot(n,v)<=0)return float4(0);
    float rainFade=rain?surfaceRainFade(dot(eyeDelta,eyeDelta),f):0;
    if(!material&&rainFade<=0)return float4(0);
    SunWet wet={0,0,1,n};
    if(rainFade>0)wet=sunWetness(saturate((light.x*16-.5)/15),saturate((light.y*16-.5)/15),n,p,sun,l);
    float3 sn=surfaceRainNormal(n,wet,rainFade);float wetWeight=wet.puddle>0?sunWetReflection(wet,saturate(dot(sn,v)))*saturate(wet.puddle*3)*rainFade:0;
    float materialWeight=material?min(.85,optics.y*(1-optics.x)*(1-optics.x)*f.state.x):0;
    if(materialWeight<=0&&wetWeight<=SUN_HAZE_UNSEEN)return float4(0);
    if(f.state.y>.5&&surfaceReflectionHidden(uint2(in.position.xy),id,normal,p,f,l,fog,depth,data,normals))return float4(0);
    float3 ray=reflect(-v,sn);float confidence,travel;
    float3 hit=waterReflection(p+n*.04,ray,n,f,scene,depth,confidence,travel);if(confidence<=0)return float4(0);
    // Independent material term lets edge-aware composition remove only the rain term outside the radius.
    float3 materialFresnel=surfaceMaterialReflectionWeight(texel,optics,dot(sn,v),f.state.x);
    float3 fresnel=materialFresnel*(1-wetWeight)+wetWeight;
    return float4(hit*fresnel*confidence,max(fresnel.r,max(fresnel.g,fresnel.b))*confidence);
}
fragment float4 surface_reflection_composite_fs(SunScreenVertex in [[stage_in]],float4 prior [[color(0)]],constant WaterFrame &f [[buffer(0)]],constant SunLighting &l [[buffer(1)]],constant Fog &fog [[buffer(2)]],constant SunFrame &sun [[buffer(3)]],
    texture2d<float> reflection [[texture(0)]],depth2d<float> depth [[texture(1)]],texture2d<float> albedo [[texture(2)]],texture2d<float> data [[texture(3)]],texture2d<float> normals [[texture(4)]],texture2d<float> terrainHistory [[texture(5)]]) {
    uint2 pixel=min(uint2(in.position.xy),uint2(f.screen.xy)-1);float4 normal=normals.read(pixel);if(normal.w<0)return prior;
    uint id=uint(round(data.read(pixel).z*255));
    bool profiled=id>0&&id<SURFACE_PROFILE_COUNT&&SURFACE_PROFILES[id].detail.w>0;
    bool rain=l.p[18].z>0&&f.state.z>0;
    if(!profiled&&!rain)return prior;
    // A zero bilinear confidence means all four contributing traces missed; skip world reconstruction and edge checks.
    constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);
    if(reflection.sample(linear,(float2(pixel)+.5)/f.screen.xy).a<=0)return prior;
    float3 p;if(!waterWorld((float2(pixel)+.5)/f.screen.xy,depth.read(pixel),f,p))return prior;
    float terrainDistance=terrainHistory.read(pixel).w;
    if(terrainDistance<=0||abs(length(p)-terrainDistance)>max(.02,terrainDistance*.0007))return prior;
    float3 eye=waterEye(f),delta=eye-p;bool insideRain=rain&&dot(delta,delta)<f.state.w;
    if(!profiled&&!insideRain)return prior;
    float2 halfPixel=(float2(pixel)+.5)/f.screen.xy*f.screen.zw-.5,blend=fract(halfPixel);int2 first=int2(floor(halfPixel));
    float4 sum=0;float total=0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
        int2 at=clamp(first+int2(x,y),int2(0),int2(f.screen.zw)-1);uint2 source=min(uint2((float2(at)+.5)/f.screen.zw*f.screen.xy),uint2(f.screen.xy)-1);
        float4 other=normals.read(source);if(other.w<0||round(data.read(source).z*255)!=id||dot(normal.xyz,other.xyz)<.95)continue;
        float3 q;if(!waterWorld((float2(source)+.5)/f.screen.xy,depth.read(source),f,q)||abs(dot(normal.xyz,q-p))>.05)continue;
        float4 reflected=reflection.read(uint2(at));
        // A coarse trace can straddle the radius. Remove its rain contribution for an outside receiver,
        // while preserving the ordinary material term. Only these boundary taps need extra wet/material work.
        float3 sourceDelta=eye-q;
        if(rain&&!insideRain&&reflected.a>0&&dot(sourceDelta,sourceDelta)<f.state.w) {
            float4 sourceLight=data.read(source);float3 texel=albedo.read(source).rgb;
            float3 optics=surfaceReflectionOptics(texel,id,l),n=normalize(other.xyz),v=normalize(sourceDelta);
            SunWet wet=sunWetness(saturate((sourceLight.x*16-.5)/15),saturate((sourceLight.y*16-.5)/15),n,q,sun,l);
            float fade=surfaceRainFade(dot(sourceDelta,sourceDelta),f);float3 sn=surfaceRainNormal(n,wet,fade);
            float w=wet.puddle>0?sunWetReflection(wet,saturate(dot(sn,v)))*saturate(wet.puddle*3)*fade:0;
            if(w>0) {
                float3 material=surfaceMaterialReflectionWeight(texel,optics,dot(sn,v),f.state.x),combined=material*(1-w)+w;
                reflected.rgb*=material/max(combined,float3(1e-8));
                reflected.a*=max(material.r,max(material.g,material.b))/max(max(combined.r,max(combined.g,combined.b)),1e-8);
            }
        }
        float weight=(x?blend.x:1-blend.x)*(y?blend.y:1-blend.y);sum+=reflected*weight;total+=weight;
    }
    if(total<=1e-6||sum.a<=0)return prior;sum/=total;
    // Keep the receiver's foreground fog layer intact while changing the surface underneath it.
    SunFog air=sunFog(normal.w,p,data.read(pixel).w,fog,sun,l);if(air.transmittance<=0)return prior;
    float3 base=waterLinear(prior.rgb,f);
    return float4(waterDisplay(sunFogEditScene(base,sum.rgb,saturate(sum.a),air,l),f),prior.a);
}
