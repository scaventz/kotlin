// !WITH_NEW_INFERENCE
val f: Boolean = true
private fun doUpdateRegularTasks() {
    try {
        while (f) {
            val xmlText = <!UNRESOLVED_REFERENCE!>getText<!>()
            if (<!DEBUG_INFO_ELEMENT_WITH_ERROR_TYPE!>xmlText<!> <!DEBUG_INFO_MISSING_UNRESOLVED{NI}, DEBUG_INFO_ELEMENT_WITH_ERROR_TYPE{OI}!>==<!> null) {}
            else {
                <!DEBUG_INFO_ELEMENT_WITH_ERROR_TYPE!>xmlText<!>.<!DEBUG_INFO_MISSING_UNRESOLVED{NI}, VARIABLE_EXPECTED{NI}, DEBUG_INFO_ELEMENT_WITH_ERROR_TYPE{OI}!>value<!> = 0 // !!!
            }
        }

    }
    finally {
        fun execute() {}
    }
}
