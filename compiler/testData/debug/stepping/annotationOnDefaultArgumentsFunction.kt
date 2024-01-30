// TARGET_BACKEND: JVM_IR
// WITH_STDLIB

// FILE: test.kt
fun getS2 = "K"
inline fun getS3() = ""
@JvmName("jvmName")
inline fun f(
    s1: String = "O",
    s2: String = getS2(),
    s3: String = getS3()
): String {
    return s1 + s2 + s3
}

fun box(): String = f()

// EXPECTATIONS JVM_IR
// test.kt:16 box
// test.kt:9 box
// test.kt:10 box
// test.kt:5 getS2
// test.kt:10 box
// test.kt:11 box
// test.kt:6 box
// test.kt:11 box
// test.kt:13 box
// test.kt:16 box
