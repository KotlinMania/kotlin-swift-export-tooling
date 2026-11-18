@file:kotlin.native.internal.objc.BindClassToObjCName(kotlin.collections.MutableList::class, "_MutableListFixture")

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.NativePtr
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.interpretObjCPointer

@kotlin.native.CName("fixture_create_list")
fun createList(): COpaquePointer = StableRef.create(mutableListOf<Any?>("initial")).asCPointer()

@kotlin.native.CName("fixture_export_array")
fun exportArray(handle: COpaquePointer): NativePtr = handle.asStableRef<MutableList<Any?>>().get().objcPtr()

@kotlin.native.CName("fixture_check_identity")
fun checkIdentity(handle: COpaquePointer, pointer: NativePtr): Boolean =
    interpretObjCPointer<Any>(pointer) === handle.asStableRef<MutableList<Any?>>().get()

@kotlin.native.CName("fixture_check_mutation")
fun checkMutation(handle: COpaquePointer): Boolean {
    val list = handle.asStableRef<MutableList<Any?>>().get()
    return list.size == 2 && list[0] == "initial" && list[1] == "from Swift"
}

@kotlin.native.CName("fixture_append_kotlin")
fun appendKotlin(handle: COpaquePointer) { handle.asStableRef<MutableList<Any?>>().get().add("from Kotlin") }

@kotlin.native.CName("fixture_dispose_list")
fun disposeList(handle: COpaquePointer) { handle.asStableRef<MutableList<Any?>>().dispose() }
