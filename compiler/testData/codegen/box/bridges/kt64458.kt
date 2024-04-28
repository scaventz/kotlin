// TARGET_BACKEND: JVM
// WITH_STDLIB

var result = "FAIL"
fun box():String {
    Impl().remove("test")
    return result
}

class Impl : Parent() {
    override fun remove(key: String): Boolean {
        return super.remove(key)
    }
}

abstract class Parent : Grandparent<String>() {
}

abstract class Grandparent<K> : MutableList<K> by mutableListOf() {
    override fun remove(key: K): Boolean {
        result = "OK"
        return true
    }
}