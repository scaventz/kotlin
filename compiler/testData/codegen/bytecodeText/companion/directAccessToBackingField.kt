class A {
    companion object {
        val s = "OK"
        var v = "NOT OK"
    }

    fun f(): String = s

    fun g() {
        v = "OK"
    }
}

// One direct `A.s` access in `f`.
// One direct `A.s` access in the accessibility bridge `access$getS$cp`.
// 2 GETSTATIC A.s

// One direct `A.v` set in `g`.
// One direct `A.v` set in the accessibility bridge `access$setV$cp`.
// One direct `A.v` set in `A.<clinit>`
// 3 PUTSTATIC A.v

// No calls to the getter/setter on the companion object.
// 0 INVOKEVIRTUAL A$Companion.getS
// 0 INVOKEVIRTUAL A$Companion.setV