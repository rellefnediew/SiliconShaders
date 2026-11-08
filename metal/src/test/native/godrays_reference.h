#pragma once
// Preserve the immutable camera.5 shader from 8d01ea0; only namespace its functions/types for a paired GPU test.
static NSString *godraysReference(NSString *path){
 NSError *error=nil;NSString *source=[NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&error];require(source!=nil,error.localizedDescription);
 source=[[[source stringByReplacingOccurrencesOfString:@"camera" withString:@"camera_ref"] stringByReplacingOccurrencesOfString:@"Camera" withString:@"CameraRef"] stringByReplacingOccurrencesOfString:@"CAMERA_" withString:@"CAMERA_REF_"];
 NSRegularExpression *constants=[NSRegularExpression regularExpressionWithPattern:@"constant bool CAMERA_REF_(\\w+) \\[\\[function_constant\\(\\d+\\)\\]\\];" options:0 error:nil];
 return [constants stringByReplacingMatchesInString:source options:0 range:NSMakeRange(0,source.length) withTemplate:@"#define CAMERA_REF_$1 CAMERA_$1"];
}
