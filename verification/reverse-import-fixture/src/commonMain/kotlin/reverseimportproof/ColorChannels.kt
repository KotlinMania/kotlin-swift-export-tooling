@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package reverseimportproof

import kotlin.native.ObjCName

public class ColorChannels(red: UByte, green: UByte, blue: UByte, paletteIndex: UByte) {
    @ObjCName(name = "rValue")
    public val r: UByte = red

    @ObjCName(name = "gValue")
    public val g: UByte = green

    @ObjCName(name = "bValue")
    public val b: UByte = blue

    @ObjCName(name = "indexValue")
    public val index: UByte = paletteIndex

    public fun r(): UByte = r
    public fun g(): UByte = g
    public fun b(): UByte = b
    public fun index(): UByte = index
}
