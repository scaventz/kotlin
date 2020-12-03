// !WITH_NEW_INFERENCE
// !CHECK_TYPE

interface A
interface B : A
interface C : B

fun test(b: B) {
    b checkType { _<B>() }
    b checkType { <!UNRESOLVED_REFERENCE_WRONG_RECEIVER{NI}, TYPE_MISMATCH{OI}!>_<!><A>() }
    b checkType { <!UNRESOLVED_REFERENCE_WRONG_RECEIVER{NI}, TYPE_MISMATCH{OI}!>_<!><C>() }
}
