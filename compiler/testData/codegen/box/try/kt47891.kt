// TARGET_BACKEND: JVM
// ISSUE: KT-47891

fun box(): String {
    try {
        try {
            run {
                return "OK"
            }
        } finally {
            return "OK"
        }
    } finally {
        return "OK"
    }
}