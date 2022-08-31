// TARGET_BACKEND: JVM_IR
// WITH_REFLECT

import kotlin.reflect.KFunction

@JvmInline
value class IC(val s: Any)

class Clazz(val ic: IC)

fun box():String {
    try {
        val a: KFunction<Clazz> = ::Clazz
        a.call(null)
    } catch (e: NullPointerException) {
        return "OK"
    }
    return "FAIL"
}