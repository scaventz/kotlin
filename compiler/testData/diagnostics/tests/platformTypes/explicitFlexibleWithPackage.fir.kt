// !DIAGNOSTICS: -UNUSED_PARAMETER
// !CHECK_TYPE
package ppp

import checkType
import _

public class ft<L, U>

fun foo(f: ft<Int, Int?>) {
    f.checkType { <!INAPPLICABLE_CANDIDATE!>_<!><Int>() }
    f.checkType { <!INAPPLICABLE_CANDIDATE!>_<!><Int?>() }
}
