// !LANGUAGE: +ContextReceivers

interface Foo {
    fun bar(): String
}

object ContextableFoo : Foo {
    context(String)
    override fun bar(): String = "OK"
}

fun box():String {
    return with("") {
        ContextableFoo.bar()
    }
}