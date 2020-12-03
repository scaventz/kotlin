// !WITH_NEW_INFERENCE
//KT-13330 AssertionError: Illegal resolved call to variable with invoke

fun foo(exec: (String.() -> Unit)?) = "".<!UNSAFE_IMPLICIT_INVOKE_CALL{NI}, WRONG_NUMBER_OF_TYPE_ARGUMENTS{NI}, UNSAFE_IMPLICIT_INVOKE_CALL{OI}!>exec<!><!WRONG_NUMBER_OF_TYPE_ARGUMENTS{OI}!><<!UNRESOLVED_REFERENCE!>caret<!>><!>() // <caret> is test data tag here
