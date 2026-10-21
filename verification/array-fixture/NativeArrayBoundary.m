#import <Foundation/Foundation.h>
#include <string.h>
#include "ArrayIntegration.h"
#include "KotlinRuntimeSupport.h"

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 2) return 2;
        void *owner = arrayintegration_StringArrays_init_allocate();
        arrayintegration_StringArrays_init_initialize__TypesOfArguments__Swift_UnsafeMutableRawPointer__(owner);
        void *values = arrayintegration_StringArrays_first(owner);
        if (strcmp(argv[1], "getNegative") == 0) { KotlinStringArrayGet(values, -1); return 10; }
        if (strcmp(argv[1], "getUpper") == 0) { KotlinStringArrayGet(values, KotlinStringArraySize(values)); return 11; }
        if (strcmp(argv[1], "setNegative") == 0) { KotlinStringArraySet(values, -1, @"invalid"); return 12; }
        if (strcmp(argv[1], "setUpper") == 0) { KotlinStringArraySet(values, KotlinStringArraySize(values), @"invalid"); return 13; }
        if (strcmp(argv[1], "wrongReceiver") == 0) { KotlinStringArraySize(owner); return 14; }
        if (strcmp(argv[1], "wrongValue") == 0) { KotlinStringArraySet(values, 0, (NSString *)@42); return 15; }
        if (strcmp(argv[1], "fromString") == 0) { KotlinStringArrayFromObjC(@"not storage"); return 16; }
        if (strcmp(argv[1], "fromNull") == 0) { KotlinStringArrayFromObjC([NSNull null]); return 17; }
        if (strcmp(argv[1], "fromObject") == 0) { KotlinStringArrayFromObjC([[NSObject alloc] init]); return 18; }
        if (strcmp(argv[1], "wrongHashReceiver") == 0) { KotlinStringArrayHash(owner); return 19; }
        if (strcmp(argv[1], "wrongEqualsReceiver") == 0) { KotlinStringArrayEquals(values, owner); return 20; }
        if (strcmp(argv[1], "wrongCopyReceiver") == 0) { KotlinStringArrayCopyRef(owner); return 21; }
        if (strcmp(argv[1], "wrongConsumeReceiver") == 0) { arrayintegration_StringArrays_accept__TypesOfArguments__KotlinRuntimeSupport_KotlinStringArray__(owner, KotlinBridgeable_String_box(@"wrong")); return 25; }
        if (strcmp(argv[1], "positive") != 0) return 3;
        void *alias = arrayintegration_StringArrays_alias(owner);
        if (!KotlinStringArrayEquals(values, alias) || KotlinStringArrayHash(values) != KotlinStringArrayHash(alias)) return 22;
        void *otherOwner = arrayintegration_StringArrays_init_allocate();
        arrayintegration_StringArrays_init_initialize__TypesOfArguments__Swift_UnsafeMutableRawPointer__(otherOwner);
        void *other = arrayintegration_StringArrays_first(otherOwner);
        if (KotlinStringArrayEquals(values, other)) return 23;
        if (!arrayintegration_StringArrays_accept__TypesOfArguments__KotlinRuntimeSupport_KotlinStringArray__(owner, KotlinStringArrayCopyRef(values))) return 24;
        KotlinBridgeable_disposeRef(alias);
        KotlinBridgeable_disposeRef(other);
        KotlinBridgeable_disposeRef(otherOwner);
        if (KotlinStringArraySize(values) != 2 || ![KotlinStringArrayGet(values, 0) isEqualToString:@"first"]) return 4;
        KotlinStringArraySet(values, 0, @"native changed");
        if (![arrayintegration_StringArrays_original__TypesOfArguments__Swift_Int32__(owner, 0) isEqualToString:@"native changed"]) return 5;
        NSObject *boxed = KotlinStringArrayToObjC(values);
        void *unboxed = KotlinStringArrayFromObjC(boxed);
        KotlinStringArraySet(unboxed, 1, @"boxed alias");
        if (![KotlinStringArrayGet(values, 1) isEqualToString:@"boxed alias"]) return 6;
        KotlinBridgeable_disposeRef(unboxed);
        KotlinBridgeable_disposeRef(values);
        KotlinBridgeable_disposeRef(owner);
        return 0;
    }
}
