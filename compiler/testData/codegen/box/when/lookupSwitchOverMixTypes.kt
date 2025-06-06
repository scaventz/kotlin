const val byte10: Byte = 10
const val short50: Short = 50

fun foo(p: Any): String {
    val newByte10: Byte = 10
    return when (p) {
        0 -> "0"
        newByte10 -> "byte10"
        short50 -> "short50"
        'z' -> "z"
        else -> "else"
    }
}

fun box(): String {
    return if (foo(0) == "0"
        && foo(byte10) == "byte10"
        && foo(short50) == "short50"
        && foo('z') == "z"
        && foo("else") == "else"
    ) "OK"
    else "FAIL"
}

// CHECK_BYTECODE_TEXT
// 1 LOOKUPSWITCH
// 0 TABLESWITCH