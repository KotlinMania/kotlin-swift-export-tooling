package arrayintegration

public class StringArrays {
    private val values: Array<String> = arrayOf("first", "last")

    public fun first(): Array<String> = values
    public fun alias(): Array<String> = values
    public fun original(index: Int): String = values[index]
    public fun accept(values: Array<String>): Boolean = values === this.values
    public fun optional(present: Boolean): Array<String>? = if (present) values else null
}

