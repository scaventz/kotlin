// TARGET_BACKEND: JVM_IR
// WITH_STDLIB

// FILE: test.kt
@JvmName("jvmName")
inline fun f(
    s1: String = "O",
    s2: String = "K"
): String = s1+s2

fun box(): String = f()

// EXPECTATIONS JVM_IR
// test.kt:11 box
// test.kt:7 box
// test.kt:8 box
// test.kt:9 box
// test.kt:11 box
