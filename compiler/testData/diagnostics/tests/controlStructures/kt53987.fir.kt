fun box(): String {
    return if (true) <!TYPE_MISMATCH!>{
        val a = 1
    }<!> else {
        "fail:"
    }
}
