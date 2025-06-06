val byte10: Byte = 10
val byte30: Byte = 30
val byte50: Byte = 50
val byte42: Byte = 42

fun foo(p: Byte): String {
    val localByte50: Byte = 50
    return when (p) {
        byte10 -> "10"
        byte30 -> "30"
        localByte50 -> "50"
        else -> "else"
    }
}

fun box(): String {
    return if (foo(byte10) == "10"
        && foo(byte30) == "30"
        && foo(byte50) == "50"
        && foo(byte42) == "else"
    ) "OK"
    else "FAIL"
}

// CHECK_BYTECODE_TEXT
// 1 LOOKUPSWITCH
// 0 TABLESWITCH