// LAMBDAS: CLASS

class Host {
    private companion object {
        fun foo() = 1
    }

    fun test() = { foo() }
}

// 0 synthetic access\$
