#import <Foundation/Foundation.h>
#include <string.h>
#include "ByteArrayProof.h"
#include "KotlinStdlib.h"
#include "KotlinRuntimeSupport.h"

int main(int argc, char **argv) {
    if (argc != 2) return 90;
    @autoreleasepool {
        const char *mode = argv[1];
        if (strcmp(mode, "negativeSize") == 0) {
            void *invalid = kotlin_ByteArray_init_create__TypesOfArguments__Swift_Int32__(-1);
            KotlinBridgeable_disposeRef(invalid);
            return 91;
        }
        if (strcmp(mode, "wrongReceiver") == 0) {
            void *owner = bytearrayproof_ByteArrayOwner_init_allocate();
            (void)bytearrayproof_ByteArrayOwner_init_initialize__TypesOfArguments__Swift_UnsafeMutableRawPointer__(owner);
            (void)kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(owner, 0);
            KotlinBridgeable_disposeRef(owner);
            return 92;
        }
        void *value = kotlin_ByteArray_init_create__TypesOfArguments__Swift_Int32__(2);
        if (strcmp(mode, "getNegative") == 0) {
            (void)kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(value, -1);
            return 93;
        }
        if (strcmp(mode, "getUpper") == 0) {
            (void)kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(value, 2);
            return 94;
        }
        if (strcmp(mode, "setNegative") == 0) {
            (void)kotlin_ByteArray_set__TypesOfArguments__Swift_Int32_Swift_Int8__(value, -1, 1);
            return 95;
        }
        if (strcmp(mode, "setUpper") == 0) {
            (void)kotlin_ByteArray_set__TypesOfArguments__Swift_Int32_Swift_Int8__(value, 2, 1);
            return 96;
        }
        if (strcmp(mode, "positive") != 0) return 97;
        if (kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(value, 0) != 0) return 98;
        if (kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(value, 1) != 0) return 99;
        kotlin_ByteArray_set__TypesOfArguments__Swift_Int32_Swift_Int8__(value, 0, -128);
        kotlin_ByteArray_set__TypesOfArguments__Swift_Int32_Swift_Int8__(value, 1, 127);
        if (kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(value, 0) != -128) return 100;
        if (kotlin_ByteArray_get__TypesOfArguments__Swift_Int32__(value, 1) != 127) return 101;
        KotlinBridgeable_disposeRef(value);
    }
    return 0;
}
