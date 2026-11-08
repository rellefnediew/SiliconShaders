// Persistent world-space light data. Updated only for world/section changes; never read back by the game.
#define BLOCK_LIGHT_DELTA 0x424c4431u
#define BLOCK_LIGHT_STEPS 16
#define BLOCK_LIGHT_SECTION_BYTES (4096u*4u)
typedef struct {
    id<MTLBuffer> staging,metadata,data,scratch,saved;
    NSMutableArray *patches,*uploadPool;
    id<MTLComputePipelineState> reference;
    uint32_t count,lightWords,workCount;int enabled,pending;
    uint64_t updates;
} OwnBlockLight;
static id<MTLBuffer> blockLightEmpty(id<MTLDevice> device) {
    static id<MTLBuffer> empty;
    if(!empty){empty=[device newBufferWithLength:64 options:MTLResourceStorageModeShared];memset(empty.contents,0,64);}
    return empty;
}
static void blockLightBind(Own *w,id<MTLRenderCommandEncoder> render) {
    OwnBlockLight *b=w?w->blockLight:NULL;
    [render setFragmentBuffer:b&&b->enabled&&b->data?b->data:blockLightEmpty(w->ctx->device) offset:0 atIndex:28];
}
// Reuse small upload buffers only after the GPU has completed their last command buffer. Full topology packets are
// infrequent and can be large; do not retain those in the pool. The pool contains at most six buffers of <= 1 MiB.
static id<MTLBuffer> blockLightUploadBuffer(OwnBlockLight *b,id<MTLDevice> device,const void *bytes,NSUInteger length) {
    if(!b->uploadPool)b->uploadPool=[NSMutableArray new];
    id<MTLBuffer> result=nil;
    @synchronized(b->uploadPool){for(NSUInteger i=0;i<b->uploadPool.count;i++){
        id<MTLBuffer> candidate=b->uploadPool[i];if(candidate.length<length)continue;
        result=[candidate retain];[b->uploadPool removeObjectAtIndex:i];break;
    }}
    if(!result)result=[device newBufferWithLength:(length+255)&~(NSUInteger)255 options:MTLResourceStorageModeShared];
    if(result)memcpy(result.contents,bytes,length);return result;
}
static BOOL blockLightListValid(const uint32_t *h,uint32_t offset,uint32_t count,uint64_t end) {
    if(count>h[0]||offset+(uint64_t)count>end)return NO;
    for(uint32_t i=0;i<count;i++)if(!h[offset+i]||h[offset+i]>h[0])return NO;
    return YES;
}
int mco_blocklight_upload(Own *w,const void *bytes,int64_t length) { @autoreleasepool {
    if(!w)return -1;
    OwnBlockLight *b=w->blockLight;
    if(!b){b=calloc(1,sizeof *b);w->blockLight=b;if(!b)return -1;}
    if(!bytes||length==0){
        w->blockLightOn=0;b->enabled=0;b->pending=0;
        [b->data release];b->data=nil;[b->scratch release];b->scratch=nil;[b->saved release];b->saved=nil;
        [b->staging release];b->staging=nil;[b->metadata release];b->metadata=nil;[b->patches removeAllObjects];
        sunFxRefresh(w);return 1;
    }
    if(length<64||length>INT_MAX)return -1;
    const uint32_t *h=bytes;BOOL patch=h[15]==BLOCK_LIGHT_DELTA;
    if(!h[0]||h[3]*4ULL!=(uint64_t)length)return -1;
    if(patch){
        const uint32_t *base=b->staging?b->staging.contents:b->metadata?b->metadata.contents:NULL;
        if(!b->enabled||!base||!base[10]||!base[12]||h[0]!=base[0]||h[14]!=base[14]||h[2]<16||h[2]+h[4]*4097ULL>h[3])return -1;
        if(!blockLightListValid(h,h[8],h[9],h[2])||!blockLightListValid(h,h[12],h[13],h[2]))return -1;
        for(uint32_t i=0;i<h[4];i++){uint32_t slot=h[h[2]+i*4097ULL];if(!slot||slot>h[0])return -1;}
    }else{
        if(h[2]<16||h[2]+h[0]*4096ULL>h[3]||h[5]+h[0]*4ULL>h[2]||h[6]+h[0]*6ULL>h[2])return -1;
        if(h[10]&&(!blockLightListValid(h,h[8],h[9],h[2])||h[8]+(uint64_t)h[0]>h[2]))return -1;
        if(h[12]&&(!blockLightListValid(h,h[12],h[13],h[2])||h[12]+(uint64_t)h[0]>h[2]))return -1;
    }
    id<MTLBuffer> upload=blockLightUploadBuffer(b,w->ctx->device,bytes,(NSUInteger)length);
    if(!upload)return -1;
    if(patch){if(!b->patches)b->patches=[NSMutableArray new];[b->patches addObject:upload];[upload release];}
    else{[b->staging release];b->staging=upload;[b->patches removeAllObjects];b->count=h[0];b->lightWords=h[3];}
    b->enabled=1;b->pending=1;w->blockLightOn=1;sunFxRefresh(w);return 1;
}}
static void blockLightSelect(const uint32_t *h,BOOL *selected,BOOL *output) {
    for(uint32_t i=0;i<(h[10]?h[9]:h[0]);i++)selected[h[10]?h[h[8]+i]-1:i]=YES;
    for(uint32_t i=0;i<(h[12]?h[13]:h[10]?h[9]:h[0]);i++)output[h[12]?h[h[12]+i]-1:h[10]?h[h[8]+i]-1:i]=YES;
}
int mco_blocklight_run(Own *w,Enc *enc,char *err,int cap) { @autoreleasepool {
    OwnBlockLight *b=w?w->blockLight:NULL;if(!b||!b->enabled||!b->pending)return 0;
    NSError *issue=nil;
    if(!b->reference){
        id<MTLFunction> f=[w->library newFunctionWithName:@"blocklight_reference"];
        b->reference=f?[w->ctx->device newComputePipelineStateWithFunction:f error:&issue]:nil;[f release];
        if(!b->reference){snprintf(err,cap,"Colored blocklight: %s",issue.localizedDescription.UTF8String);return -1;}
    }
    const uint32_t *h=b->staging?b->staging.contents:b->metadata.contents,*old=b->metadata?b->metadata.contents:NULL;
    NSUInteger lightBytes=(NSUInteger)b->count*BLOCK_LIGHT_SECTION_BYTES,headerBytes=(NSUInteger)b->lightWords*4,prefixBytes=(NSUInteger)h[2]*4;
    id<MTLBuffer> metadata=[w->ctx->device newBufferWithBytes:h length:prefixBytes options:MTLResourceStorageModeShared];
    BOOL *selected=calloc(b->count,sizeof(BOOL)),*output=calloc(b->count,sizeof(BOOL));
    if(!metadata||!selected||!output){[metadata release];free(selected);free(output);return -1;}
    uint32_t *next=metadata.contents;
    if(b->staging)blockLightSelect(h,selected,output);
    for(id<MTLBuffer> patch in b->patches){const uint32_t *p=patch.contents;blockLightSelect(p,selected,output);next[7]=p[7];next[11]=p[11];}
    uint32_t workCount=0,outputCount=0,saveCount=0;
    for(uint32_t i=0;i<b->count;i++){
        if(output[i])selected[i]=YES;
        if(selected[i]){if(h[10])next[h[8]+workCount]=i+1;workCount++;if(!output[i])saveCount++;}
        if(output[i]){if(h[12])next[h[12]+outputCount]=i+1;outputCount++;}
    }
    if(h[10])next[9]=workCount;if(h[12])next[13]=outputCount;b->workCount=workCount;
    BOOL same=old&&old[0]==h[0]&&old[3]==h[3]&&old[5]==h[5]&&!memcmp(old+old[5],h+h[5],h[0]*16);
    id<MTLBuffer> data=same?[b->data retain]:[w->ctx->device newBufferWithLength:headerBytes+lightBytes options:MTLResourceStorageModePrivate];
    id<MTLBuffer> scratch=!workCount?nil:b->scratch&&b->scratch.length>=lightBytes?[b->scratch retain]:[w->ctx->device newBufferWithLength:lightBytes options:MTLResourceStorageModePrivate];
    NSUInteger saveBytes=(NSUInteger)saveCount*BLOCK_LIGHT_SECTION_BYTES;
    id<MTLBuffer> saved=!saveCount?nil:b->saved&&b->saved.length>=saveBytes?[b->saved retain]:[w->ctx->device newBufferWithLength:saveBytes options:MTLResourceStorageModePrivate];
    if(!data||(workCount&&!scratch)||(saveCount&&!saved)){
        [metadata release];[data release];[scratch release];[saved release];free(selected);free(output);
        snprintf(err,cap,"Cannot allocate native colored light buffers");return -1;
    }
    mc_pre_end_encoders(enc);id<MTLCommandBuffer> cmd=mc_pre(enc);
    id<MTLBlitCommandEncoder> blit=mc_profiled_blit(enc,cmd);blit.label=@"colored light: section deltas";
    [blit copyFromBuffer:metadata sourceOffset:0 toBuffer:data destinationOffset:0 size:prefixBytes];
    if(b->staging)[blit copyFromBuffer:b->staging sourceOffset:prefixBytes toBuffer:data destinationOffset:prefixBytes size:headerBytes-prefixBytes];
    for(id<MTLBuffer> patch in b->patches){const uint32_t *p=patch.contents;
        for(uint32_t i=0;i<p[4];i++){NSUInteger record=p[2]+(NSUInteger)i*4097,slot=p[record]-1;
            [blit copyFromBuffer:patch sourceOffset:(record+1)*4 toBuffer:data destinationOffset:prefixBytes+slot*BLOCK_LIGHT_SECTION_BYTES size:BLOCK_LIGHT_SECTION_BYTES];
        }
    }
    if(!same){
        [blit fillBuffer:data range:NSMakeRange(headerBytes,lightBytes) value:0];
        for(uint32_t i=0;old&&b->data&&i<b->count;i++)if(!output[i]){
            const uint32_t *key=h+h[5]+i*4;uint32_t mask=old[1],probe=(key[0]*73856093u^key[1]*19349663u^key[2]*83492791u)&mask;
            for(uint32_t step=0;step<=mask;step++){
                const uint32_t *entry=old+old[4]+probe*4;if(!entry[3])break;
                if(!memcmp(key,entry,12)){[blit copyFromBuffer:b->data sourceOffset:(NSUInteger)old[3]*4+(NSUInteger)(entry[3]-1)*BLOCK_LIGHT_SECTION_BYTES toBuffer:data destinationOffset:headerBytes+(NSUInteger)i*BLOCK_LIGHT_SECTION_BYTES size:BLOCK_LIGHT_SECTION_BYTES];break;}
                probe=(probe+1)&mask;
            }
        }
    }
    // Preserve the computation-only halo, then start every selected cell from zero. Halo cells are restored after the
    // solve; outputs are >=17 cell edges from any unselected section and cannot observe its cached values in 16 steps.
    uint32_t saveIndex=0;
    for(uint32_t i=0;i<b->count;i++)if(selected[i]&&!output[i]){
        [blit copyFromBuffer:data sourceOffset:headerBytes+(NSUInteger)i*BLOCK_LIGHT_SECTION_BYTES toBuffer:saved destinationOffset:(NSUInteger)saveIndex++*BLOCK_LIGHT_SECTION_BYTES size:BLOCK_LIGHT_SECTION_BYTES];
    }
    for(uint32_t i=0;i<b->count;i++)if(selected[i]){
        uint32_t end=i+1;while(end<b->count&&selected[end])end++;
        [blit fillBuffer:data range:NSMakeRange(headerBytes+(NSUInteger)i*BLOCK_LIGHT_SECTION_BYTES,(NSUInteger)(end-i)*BLOCK_LIGHT_SECTION_BYTES) value:0];i=end-1;
    }
    // Initialize fixed neighbours in both ping-pong buffers. They only affect the discarded outer halo.
    if(workCount&&workCount<b->count)for(uint32_t i=0;i<b->count;i++)if(!selected[i]){
        BOOL touches=NO;for(uint32_t k=0;k<6&&!touches;k++){uint32_t n=h[h[6]+i*6+k];touches=n&&selected[n-1];}
        if(touches)[blit copyFromBuffer:data sourceOffset:headerBytes+(NSUInteger)i*BLOCK_LIGHT_SECTION_BYTES toBuffer:scratch destinationOffset:(NSUInteger)i*BLOCK_LIGHT_SECTION_BYTES size:BLOCK_LIGHT_SECTION_BYTES];
    }
    [blit endEncoding];[blit release];
    if(workCount){
        id<MTLComputeCommandEncoder> compute=mc_profiled_compute(enc,cmd,MTLDispatchTypeSerial);compute.label=@"colored light: bounded RGB propagation";
        [compute setComputePipelineState:b->reference];[compute setBuffer:data offset:0 atIndex:0];
        // A bounded transport window, not a convergence claim. Source injection occupies step one, leaving 15 edges.
        for(int i=0;i<BLOCK_LIGHT_STEPS;i++){
            [compute setBuffer:(i&1)?scratch:data offset:(i&1)?0:headerBytes atIndex:1];
            [compute setBuffer:(i&1)?data:scratch offset:(i&1)?headerBytes:0 atIndex:2];
            [compute dispatchThreads:MTLSizeMake((NSUInteger)workCount*4096,1,1) threadsPerThreadgroup:MTLSizeMake(MIN(128,b->reference.maxTotalThreadsPerThreadgroup),1,1)];
            [compute memoryBarrierWithScope:MTLBarrierScopeBuffers];
        }
        [compute endEncoding];[compute release];
    }
    if(saveCount){
        id<MTLBlitCommandEncoder> restore=mc_profiled_blit(enc,cmd);restore.label=@"colored light: retain unaffected halo";saveIndex=0;
        for(uint32_t i=0;i<b->count;i++)if(selected[i]&&!output[i]){
            [restore copyFromBuffer:saved sourceOffset:(NSUInteger)saveIndex++*BLOCK_LIGHT_SECTION_BYTES toBuffer:data destinationOffset:headerBytes+(NSUInteger)i*BLOCK_LIGHT_SECTION_BYTES size:BLOCK_LIGHT_SECTION_BYTES];
        }
        [restore endEncoding];[restore release];
    }
    free(selected);free(output);
    // The command buffer retains payloads while the GPU reads them. A completion handler returns only small buffers
    // to the pool, so CPU uploads never overwrite GPU-visible in-flight contents and never wait for the GPU.
    NSMutableArray *used=[NSMutableArray array];if(b->staging)[used addObject:b->staging];[used addObjectsFromArray:b->patches?:@[]];
    NSMutableArray *pool=b->uploadPool;
    [cmd addCompletedHandler:^(id<MTLCommandBuffer> completed){@synchronized(pool){for(id<MTLBuffer> upload in used)if(upload.length<=1024*1024&&pool.count<6)[pool addObject:upload];}}];
    [b->data release];b->data=data;if(scratch){[b->scratch release];b->scratch=scratch;}if(saved){[b->saved release];b->saved=saved;}
    [b->metadata release];b->metadata=metadata;[b->staging release];b->staging=nil;[b->patches removeAllObjects];b->pending=0;b->updates++;return 1;
}}
// Controlled native test access only. The game never requests CPU-visible field data.
id<MTLBuffer> mco_blocklight_debug_buffer(Own *w){OwnBlockLight *b=w?w->blockLight:NULL;return b?b->data:nil;}
// Compatibility with older test harnesses: there is one production kernel, no obsolete tiled variant.
void mco_blocklight_debug_reference(Own *w,int enabled){(void)w;(void)enabled;}
