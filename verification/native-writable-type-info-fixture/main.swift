import Foundation
import CollectionBridge

let handle = fixture_create_list()!
defer { fixture_dispose_list(handle) }
let pointer = fixture_export_array(handle)!
let array = Unmanaged<NSMutableArray>.fromOpaque(pointer).takeUnretainedValue()
precondition(array.count == 1 && array[0] as? String == "initial")
precondition(fixture_check_identity(handle, Unmanaged.passUnretained(array).toOpaque()))
array.add("from Swift")
precondition(fixture_check_mutation(handle))
fixture_append_kotlin(handle)
precondition(array.count == 3 && array[2] as? String == "from Kotlin")
precondition(fixture_check_identity(handle, Unmanaged.passUnretained(array).toOpaque()))
print("Foundation conversion, bidirectional mutation and Kotlin identity passed")
