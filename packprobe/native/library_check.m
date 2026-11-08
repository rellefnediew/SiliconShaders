// Compile translated scene programs using original siliconshaders's actual Metal library entry point.
// Does not draw a scene, launch Minecraft, or claim an image/FPS result.
#import <Foundation/Foundation.h>
#import "mcmetal.h"
extern Ctx *mc_create(char *,int,char *,int);
extern id<MTLLibrary> mc_library_new(Ctx *,const char *,char *,int);
extern void mc_release(id);

static MTLVertexFormat attributeFormat(MTLDataType type,NSUInteger *bytes) {
    switch(type) {
        case MTLDataTypeFloat:*bytes=4;return MTLVertexFormatFloat;
        case MTLDataTypeFloat2:*bytes=8;return MTLVertexFormatFloat2;
        case MTLDataTypeFloat3:*bytes=12;return MTLVertexFormatFloat3;
        case MTLDataTypeFloat4:*bytes=16;return MTLVertexFormatFloat4;
        case MTLDataTypeInt:*bytes=4;return MTLVertexFormatInt;
        case MTLDataTypeInt2:*bytes=8;return MTLVertexFormatInt2;
        case MTLDataTypeInt3:*bytes=12;return MTLVertexFormatInt3;
        case MTLDataTypeInt4:*bytes=16;return MTLVertexFormatInt4;
        case MTLDataTypeUInt:*bytes=4;return MTLVertexFormatUInt;
        case MTLDataTypeUInt2:*bytes=8;return MTLVertexFormatUInt2;
        case MTLDataTypeUInt3:*bytes=12;return MTLVertexFormatUInt3;
        case MTLDataTypeUInt4:*bytes=16;return MTLVertexFormatUInt4;
        default:*bytes=0;return MTLVertexFormatInvalid;
    }
}

int main(int argc,const char **argv) {
    @autoreleasepool {
        if(argc!=3) { fprintf(stderr,"Usage: library_check TRANSLATION_REPORT.json OUTPUT.json\n");return 2; }
        NSString *input=[NSString stringWithUTF8String:argv[1]];
        NSError *error=nil;
        NSData *data=[NSData dataWithContentsOfFile:input options:0 error:&error];
        NSDictionary *report=data?[NSJSONSerialization JSONObjectWithData:data options:0 error:&error]:nil;
        if(!report) { fprintf(stderr,"%s\n",error.localizedDescription.UTF8String);return 2; }
        char device[256]={0},problem[16384]={0};
        Ctx *ctx=mc_create(device,sizeof(device),problem,sizeof(problem));
        if(!ctx) { fprintf(stderr,"%s\n",problem);return 2; }
        NSMutableArray *results=[NSMutableArray array];int passed=0,failed=0;
        NSMutableDictionary *functions=[NSMutableDictionary dictionary],*stageInfo=[NSMutableDictionary dictionary];
        for(NSDictionary *stage in report[@"stages"]) {
            if(![stage[@"status"] isEqualToString:@"translated"]) continue;
            @autoreleasepool {
                NSString *path=[input.stringByDeletingLastPathComponent stringByAppendingPathComponent:stage[@"metalFile"]];
                NSString *source=[NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&error];
                problem[0]=0;
                id<MTLLibrary> library=source?mc_library_new(ctx,source.UTF8String,problem,sizeof(problem)):nil;
                id<MTLFunction> function=library?[library newFunctionWithName:stage[@"entryPoint"]]:nil;
                NSMutableDictionary *row=[NSMutableDictionary dictionaryWithDictionary:@{@"source":stage[@"source"],@"status":function?@"loaded":@"failed"}];
                if(function) {
                    passed++;
                    row[@"functionType"]=@(function.functionType);
                    functions[stage[@"source"]]=function;stageInfo[stage[@"source"]]=stage;
                } else {
                    failed++;
                    row[@"error"]=problem[0]?[NSString stringWithUTF8String:problem]:error.localizedDescription?:@"Entry point missing";
                }
                [results addObject:row];
                [function release];if(library) mc_release(library);
            }
        }
        NSMutableArray *pipelines=[NSMutableArray array];int linked=0,linkFailures=0;
        for(NSString *key in [[functions allKeys] sortedArrayUsingSelector:@selector(compare:)]) {
            if(![key hasSuffix:@".vsh"]) continue;
            NSString *stem=key.stringByDeletingPathExtension,*fragmentKey=[stem stringByAppendingString:@".fsh"];
            id<MTLFunction> vertex=functions[key],fragment=functions[fragmentKey];
            if(!fragment) continue;
            MTLRenderPipelineDescriptor *desc=[[[MTLRenderPipelineDescriptor alloc] init] autorelease];
            desc.vertexFunction=vertex;desc.fragmentFunction=fragment;
            desc.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
            MTLVertexDescriptor *layout=[MTLVertexDescriptor vertexDescriptor];NSUInteger stride=0;BOOL supported=YES;
            for(MTLVertexAttribute *attribute in vertex.vertexAttributes) {
                if(!attribute.active) continue;
                NSUInteger size=0;MTLVertexFormat format=attributeFormat(attribute.attributeType,&size);
                if(format==MTLVertexFormatInvalid || attribute.attributeIndex>=31) { supported=NO;break; }
                layout.attributes[attribute.attributeIndex].format=format;
                layout.attributes[attribute.attributeIndex].offset=stride;
                layout.attributes[attribute.attributeIndex].bufferIndex=30;stride+=size;
            }
            if(stride) { layout.layouts[30].stride=stride;desc.vertexDescriptor=layout; }
            for(NSDictionary *attribute in stageInfo[fragmentKey][@"interfaces"][@"outputs"]) {
                NSUInteger index=[attribute[@"location"] unsignedIntegerValue];
                if(index>=8) { supported=NO;break; }
                desc.colorAttachments[index].pixelFormat=MTLPixelFormatRGBA16Float;
            }
            NSError *linkError=nil;
            id<MTLRenderPipelineState> pipeline=supported?[ctx->device newRenderPipelineStateWithDescriptor:desc error:&linkError]:nil;
            NSMutableDictionary *row=[NSMutableDictionary dictionaryWithDictionary:@{@"program":stem,@"status":pipeline?@"linked":@"failed"}];
            if(pipeline) linked++;else { linkFailures++;row[@"error"]=linkError.localizedDescription?:@"Unsupported stage interface"; }
            [pipelines addObject:row];[pipeline release];
        }
        NSDictionary *output=@{@"scope":@"Metal libraries and render pipeline link check on siliconshaders; no scene execution or FPS measurement",
            @"device":[NSString stringWithUTF8String:device],@"loaded":@(passed),@"failed":@(failed),@"stages":results,
            @"linkedPipelines":@(linked),@"pipelineFailures":@(linkFailures),@"pipelines":pipelines};
        NSData *json=[NSJSONSerialization dataWithJSONObject:output options:NSJSONWritingPrettyPrinted error:&error];
        if(!json || ![json writeToFile:[NSString stringWithUTF8String:argv[2]] options:NSDataWritingAtomic error:&error]) {
            fprintf(stderr,"%s\n",error.localizedDescription.UTF8String);return 2;
        }
        printf("siliconshaders Metal check: loaded=%d failed=%d linked=%d linkFailures=%d device=%s\n",passed,failed,linked,linkFailures,device);
        return failed||linkFailures?1:0;
    }
}
