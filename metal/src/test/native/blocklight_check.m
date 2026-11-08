#include "sun_test_common.h"
extern id<MTLCommandBuffer> mc_pre(Enc *);
extern int mco_blocklight_upload(void *,const void *,int64_t);
extern int mco_blocklight_run(void *,Enc *,char *,int);
extern id<MTLBuffer> mco_blocklight_debug_buffer(void *);
extern void mco_blocklight_debug_reference(void *,int);
static void finish(Enc *e){id<MTLCommandBuffer> c=mc_enc_commit(e);[c waitUntilCompleted];require(c.status==MTLCommandBufferStatusCompleted,c.error.localizedDescription);}
static uint32_t *packet(int *words){
    int hash=4,table=16,coords=table+hash*4,neighbors=coords+8,cells=neighbors+12,lights=cells+8192;*words=lights;
    uint32_t *p=calloc(lights,4);p[0]=2;p[1]=hash-1;p[2]=cells;p[3]=lights;p[4]=table;p[5]=coords;p[6]=neighbors;
    for(int i=0;i<2;i++){
        int at=(i*73856093)&3;p[table+at*4]=i;p[table+at*4+3]=i+1;p[coords+i*4]=i;
        p[neighbors+i*6+(i?1:0)]=i?1:2;
    }
    for(int z=0;z<16;z++)for(int y=0;y<16;y++){
        p[cells+8+z*16+y*256]=63u<<26;
        p[cells+4096+z*16+y*256]=(1u<<25)|255u|(26u<<8)|(26u<<16);
    }
    p[cells+2+8*16+8*256]=255u;
    p[cells+12+8*16+8*256]=255u<<8;
    p[cells+4096+6+8*16+8*256]=255u<<16;
    return p;
}
static uint8_t *readPending(Ctx *ctx,Enc *enc,void *own,int words,int sections,double *milliseconds){
    char err[4096]={0};require(mco_blocklight_run(own,enc,err,sizeof err)>0,@(err));
    id<MTLBuffer> data=mco_blocklight_debug_buffer(own),read=[ctx->device newBufferWithLength:(NSUInteger)sections*4096*4 options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> b=mc_pre(enc).blitCommandEncoder;
    [b copyFromBuffer:data sourceOffset:words*4 toBuffer:read destinationOffset:0 size:(NSUInteger)sections*4096*4];[b endEncoding];
    id<MTLCommandBuffer> pre=[mc_pre(enc) retain];id<MTLCommandBuffer> c=mc_enc_commit(enc);[c waitUntilCompleted];require(c.status==MTLCommandBufferStatusCompleted,c.error.localizedDescription);
    *milliseconds=(pre.GPUEndTime-pre.GPUStartTime)*1000;[pre release];
    uint8_t *values=malloc((NSUInteger)sections*4096*4);memcpy(values,read.contents,(NSUInteger)sections*4096*4);[read release];return values;
}
static uint8_t *solve(Ctx *ctx,Enc *enc,void *own,uint32_t *p,int words,int reference,double *milliseconds){
    require(mco_blocklight_upload(own,p,words*4)>0,@"Coefficient upload rejected");mco_blocklight_debug_reference(own,reference);
    return readPending(ctx,enc,own,words,p[0],milliseconds);
}
// Compact stable-layout packet, with independent compute and output sets. A count of zero means a header-only update.
static uint32_t *delta(uint32_t *full,int count,int changedSlot,uint32_t replacement,int *words){
    int compute=count?(count+1<(int)full[0]?count+1:(int)full[0]):0;
    int records=16+compute+count;*words=records+(changedSlot?4097:0);uint32_t *p=calloc(*words,4);
    p[0]=full[0];p[2]=records;p[3]=*words;p[4]=changedSlot?1:0;p[7]=full[7];p[8]=16;p[9]=compute;p[10]=1;p[11]=full[11];
    p[12]=16+compute;p[13]=count;p[14]=full[14];p[15]=0x424c4431u;
    for(int i=0;i<compute;i++)p[16+i]=i+1;for(int i=0;i<count;i++)p[p[12]+i]=i+1;
    if(changedSlot){p[records]=changedSlot;memcpy(p+records+1,full+full[2]+(changedSlot-1)*4096,4096*4);p[records+1+15+8*16+8*256]=replacement;}
    return p;
}
static float value(uint8_t *v,int x,int y,int z,int c){return (float)v[((x/16)*4096+(x&15)+z*16+y*256)*4+c];}
int main(int argc,char **argv){@autoreleasepool{
    require(argc==3,@"Usage: blocklight_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    char err[16384]={0},gpu[256];Ctx *ctx=mc_create(gpu,sizeof gpu,err,sizeof err);require(ctx!=NULL,@(err));Enc *enc=mc_enc_new(ctx);
    void *own=mco_new(ctx,source.UTF8String,0,0,0,err,sizeof err);require(own!=NULL,@(err));int words;uint32_t *p=packet(&words);
    NSError *error=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;
    NSString *probe=@"\nfragment float4 block_probe(float4 q[[position]],device const uint *d[[buffer(28)]],constant SunFrame &sun[[buffer(0)]]){float x=q.x<1?4.5:9.5;return sunBlockLight(float3(x,8.5,8.5),float3(0),sun,d);}";
    id<MTLLibrary> library=[ctx->device newLibraryWithSource:[source stringByAppendingString:probe] options:options error:&error];require(library!=nil,error.localizedDescription);
    MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool yes=true,no=false;int zero=0;float cutoff=.1;
    for(int k=0;k<=30;k++){if(k==5)[fc setConstantValue:&cutoff type:MTLDataTypeFloat atIndex:k];else if(k==7)[fc setConstantValue:&zero type:MTLDataTypeInt atIndex:k];else [fc setConstantValue:(k==26||k==30||k==4)?&yes:&no type:MTLDataTypeBool atIndex:k];}
    [fc setConstantValue:&no type:MTLDataTypeBool atIndex:52];id<MTLFunction> water=[library newFunctionWithName:@"own_fs" constantValues:fc error:&error];require(water!=nil,error.localizedDescription);[water release];
    double refTime,optTime;uint8_t *ref=solve(ctx,enc,own,p,words,1,&refTime),*opt=solve(ctx,enc,own,p,words,0,&optTime);
    require(!memcmp(ref,opt,8192*4),@"Repeated propagation differs");
    require(value(opt,4,8,8,0)>0&&value(opt,9,8,8,0)==0,@"Opaque wall leaks red light");
    require(value(opt,18,8,8,1)>0&&value(opt,18,8,8,2)>0,@"Colored sources fail to mix across section boundary/glass");
    require(value(opt,15,8,8,1)>value(opt,17,8,8,1),@"Stained glass does not attenuate green");
    MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[library newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[library newFunctionWithName:@"block_probe"];pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;
    id<MTLRenderPipelineState> probePipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&error];require(probePipeline!=nil,error.localizedDescription);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:2 height:1 mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;
    id<MTLTexture> image=[ctx->device newTextureWithDescriptor:td];id<MTLTexture> targets[]={image};float clear[]={0,0,0,0,0};
    mc_render_begin(enc,1,targets,clear,nil,0,0,2,1);[enc->render setRenderPipelineState:probePipeline];Sun probeSun={0};[enc->render setFragmentBytes:&probeSun length:sizeof probeSun atIndex:0];[enc->render setCullMode:MTLCullModeNone];[enc->render setFragmentBuffer:mco_blocklight_debug_buffer(own) offset:0 atIndex:28];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];finish(enc);
    _Float16 pixels[8];[image getBytes:pixels bytesPerRow:16 fromRegion:MTLRegionMake2D(0,0,2,1) mipmapLevel:0];require(pixels[0]>0&&pixels[4]==0,@"Surface interpolation leaks through wall or loses light");
    int blue=p[2]+4096+6+8*16+8*256;p[blue]=0;double removalTime;uint8_t *removed=solve(ctx,enc,own,p,words,0,&removalTime);
    for(int i=0;i<8192;i++)require(removed[i*4+2]==0,@"Removed source leaves blue light");
    // Independent component: updating the first two sections must preserve the third exactly.
    int hash=8,table=16,coords=table+hash*4,neighbors=coords+12,work=neighbors+18,cells=work+3,words3=(cells+3*4096+3)&~3;
    uint32_t *p3=calloc(words3,4);p3[0]=3;p3[1]=hash-1;p3[2]=cells;p3[3]=words3;p3[4]=table;p3[5]=coords;p3[6]=neighbors;p3[8]=work;p3[9]=3;p3[10]=1;
    for(int j=0;j<3;j++){
        int x=j==2?7:j,at=(x*73856093)&7;while(p3[table+at*4+3])at=(at+1)&7;
        p3[table+at*4]=x;p3[table+at*4+3]=j+1;p3[coords+j*4]=x;p3[work+j]=j+1;
    }
    p3[neighbors]=2;p3[neighbors+7]=1;memcpy(p3+cells,p+p[2],8192*4);p3[cells+8192+8+8*16+8*256]=255u<<16;
    double unused;uint8_t *all=solve(ctx,enc,own,p3,words3,1,&unused);
    p3[cells+2+8*16+8*256]=0;p3[9]=2;uint8_t *partial=solve(ctx,enc,own,p3,words3,1,&unused);
    require(!memcmp(all+8192*4,partial+8192*4,4096*4),@"Unchanged disconnected component was lost");
    p3[9]=3;uint8_t *full=solve(ctx,enc,own,p3,words3,1,&unused);
    require(!memcmp(full,partial,3*4096*4),@"Component caching differs from a full rebuild");
    free(all);free(partial);free(full);free(p3);
    // Connected sections: the shared alpha must not relay a source beyond the finite 15-edge transport window.
    int commit=work+3,cells4=commit+3,words4=(cells4+3*4096+3)&~3;
    uint32_t *p4=calloc(words4,4);p4[0]=3;p4[1]=7;p4[2]=cells4;p4[3]=words4;p4[4]=table;p4[5]=coords;p4[6]=neighbors;
    p4[8]=work;p4[9]=3;p4[10]=1;p4[11]=64;p4[12]=commit;p4[13]=3;p4[14]=42;
    for(int j=0;j<3;j++){
        int at=(j*73856093)&7;while(p4[table+at*4+3])at=(at+1)&7;
        p4[table+at*4]=j;p4[table+at*4+3]=j+1;p4[coords+j*4]=j;p4[work+j]=j+1;p4[commit+j]=j+1;
        if(j<2)p4[neighbors+j*6]=j+2;if(j>0)p4[neighbors+j*6+1]=j;
    }
    uint32_t torch=238u|(138u<<8)|(60u<<16),seaLantern=184u|(222u<<8)|(255u<<16);
    for(int i=0;i<3*4096;i++)p4[cells4+i]=torch;
    int sourceCell=15+8*16+8*256;p4[cells4+sourceCell]=seaLantern;
    uint8_t *before=solve(ctx,enc,own,p4,words4,1,&unused);
    require(value(before,32,8,8,2)==60,@"Shared alpha relays a colour more than 15 edges");
    int patchWords;uint32_t *patch=delta(p4,2,1,torch,&patchWords);
    require(mco_blocklight_upload(own,patch,patchWords*4)>0,@"Section delta rejected");free(patch);
    uint8_t *local=readPending(ctx,enc,own,words4,3,&unused);
    require(!memcmp(before+8192*4,local+8192*4,4096*4),@"Computation-only halo was overwritten");
    p4[cells4+sourceCell]=torch;uint8_t *rebuilt=solve(ctx,enc,own,p4,words4,1,&unused);
    require(!memcmp(local,rebuilt,3*4096*4),@"Connected local rebuild differs from full rebuild");
    // Range-only update: no coefficient records or compute work, but the new header must reach the GPU.
    patch=delta(p4,0,0,0,&patchWords);patch[11]=96;
    require(patchWords==16&&mco_blocklight_upload(own,patch,patchWords*4)>0,@"Header-only delta rejected");free(patch);
    uint8_t *ranged=readPending(ctx,enc,own,words4,3,&unused);require(!memcmp(ranged,rebuilt,3*4096*4),@"Range-only update changes RGB cells");
    id<MTLBuffer> header=[ctx->device newBufferWithLength:64 options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> checkHeader=mc_pre(enc).blitCommandEncoder;
    [checkHeader copyFromBuffer:mco_blocklight_debug_buffer(own) sourceOffset:0 toBuffer:header destinationOffset:0 size:64];[checkHeader endEncoding];finish(enc);
    require(((uint32_t *)header.contents)[11]==96,@"Range-only delta lost the new fade distance");[header release];
    // A full packet followed by two queued deltas must retain both changes and the union of their update sets.
    require(mco_blocklight_upload(own,p4,words4*4)>0,@"Queued full upload rejected");
    patch=delta(p4,2,1,seaLantern,&patchWords);require(mco_blocklight_upload(own,patch,patchWords*4)>0,@"Queued delta one rejected");free(patch);
    patch=delta(p4,3,3,seaLantern,&patchWords);require(mco_blocklight_upload(own,patch,patchWords*4)>0,@"Queued delta two rejected");free(patch);
    uint8_t *queued=readPending(ctx,enc,own,words4,3,&unused);
    p4[cells4+sourceCell]=seaLantern;p4[cells4+8192+sourceCell]=seaLantern;
    uint8_t *queuedFull=solve(ctx,enc,own,p4,words4,1,&unused);require(!memcmp(queued,queuedFull,3*4096*4),@"Queued deltas differ from latest full snapshot");
    printf("bounded/local: 17-block blue = %.0f/255 (expected 60), exact connected local/full equality, retained halo, 64-byte range update, queued full/delta union\n",value(before,32,8,8,2));
    free(before);free(local);free(rebuilt);free(ranged);free(queued);free(queuedFull);free(p4);
    // A lit fixed neighbour outside the computation halo must not advance the finite transport window. This also
    // exercises saving/restoring a nonuniform halo and the partial ping-pong border initialization.
    int coords5=48,neighbors5=64,work5=88,commit5=92,cells5=96,words5=cells5+4*4096;
    uint32_t *p5=calloc(words5,4);p5[0]=4;p5[1]=7;p5[2]=cells5;p5[3]=words5;p5[4]=16;p5[5]=coords5;p5[6]=neighbors5;
    p5[8]=work5;p5[9]=4;p5[10]=1;p5[11]=64;p5[12]=commit5;p5[13]=4;p5[14]=43;
    for(int j=0;j<4;j++){
        int at=(j*73856093)&7;p5[16+at*4]=j;p5[16+at*4+3]=j+1;p5[coords5+j*4]=j;p5[work5+j]=j+1;p5[commit5+j]=j+1;
        if(j<3)p5[neighbors5+j*6]=j+2;if(j>0)p5[neighbors5+j*6+1]=j;
    }
    for(int i=0;i<4*4096;i++)p5[cells5+i]=torch;
    p5[cells5+sourceCell]=seaLantern;p5[cells5+3*4096+8*16+8*256]=seaLantern;
    uint8_t *borderBefore=solve(ctx,enc,own,p5,words5,1,&unused);
    patch=delta(p5,2,1,torch,&patchWords);require(mco_blocklight_upload(own,patch,patchWords*4)>0,@"Border delta rejected");free(patch);
    uint8_t *borderLocal=readPending(ctx,enc,own,words5,4,&unused);
    require(!memcmp(borderBefore+8192*4,borderLocal+8192*4,8192*4),@"Nonuniform halo/fixed section changed");
    p5[cells5+sourceCell]=torch;uint8_t *borderFull=solve(ctx,enc,own,p5,words5,1,&unused);
    require(!memcmp(borderLocal,borderFull,4*4096*4)&&value(borderLocal,31,8,8,2)==60,@"Fixed external light advanced the transport window");
    printf("fixed border: nonuniform halo retained; lit external neighbour cannot reach output in 16 steps; local/full exact\n");
    free(borderBefore);free(borderLocal);free(borderFull);free(p5);
    require(mco_blocklight_run(own,enc,err,sizeof err)==0,@"Unchanged field recomputes");
    printf("PASS %s: two sections, RGB mixing, wall, stained glass, source removal, repeatable propagation, surface sampler, water specialization, exact component reuse, bounded connected updates, header-only updates, queued deltas, cached field. Diagnostic command times %.3f ms / %.3f ms (first-use compilation/readback included; not a performance claim)\n",gpu,refTime,optTime);
    free(ref);free(opt);free(removed);free(p);return 0;
}}
