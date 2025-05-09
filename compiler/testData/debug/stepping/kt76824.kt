// TARGET_BACKEND: JVM_IR
// FILE: test.kt

class A {
    fun f(flag: Boolean) {
        if (flag) {
            toString()
        }
        super.toString()
    }
}

fun box() {
    val a = A()
    a.f(false)
}

// EXPECTATIONS JVM_IR
// test.kt:6 f
// test.kt:8 f
// test.kt:9 f