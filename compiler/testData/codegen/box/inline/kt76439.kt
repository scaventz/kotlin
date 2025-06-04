// TARGET_PLATFORM: JVM

open class A<T> {
    inline fun test(p: T) {
        p.toString()
    }
}

class B : A<Function0<String>>()

fun box(): String {
    B().test { "42" }
    return "OK"
}