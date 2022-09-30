// TARGET_BACKEND: JVM_IR

// FILE: I.java
public interface I<T> {
    String f(T id);
}

// FILE: A.kt
class A(x: I<Long>) : I<Long> by x {
    override fun f(id: Long): String = "OK"
}

fun box(): String {
    val i = I<Long> { "42" }
    return A(i).f(42)
}