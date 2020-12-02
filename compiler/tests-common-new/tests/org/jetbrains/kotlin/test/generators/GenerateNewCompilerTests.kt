/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.generators

import org.jetbrains.kotlin.generators.util.TestGeneratorUtil
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.runners.*

fun main(args: Array<String>) {
    val excludedFirTestdataPattern = "^(.+)\\.fir\\.kts?\$"

    generateNewTestGroupSuite(args) {
        testGroup("compiler/tests-common-new/tests-gen", "compiler/testData") {
            testClass<AbstractNewDiagnosticTest> {
                model("diagnostics/tests", pattern = "^(.*)\\.kts?$", excludedPattern = excludedFirTestdataPattern)
            }

            testClass<AbstractFirOldFrontendDiagnosticsTest> {
                model("diagnostics/tests", excludedPattern = excludedFirTestdataPattern)
            }

            testClass<AbstractFirOldFrontendDiagnosticsTestWithStdlib> {
                model(
                    "diagnostics/testsWithStdLib",
                    excludedPattern = excludedFirTestdataPattern,
                )
            }

            testClass<AbstractNewBlackBoxCodegenTest> {
                model("codegen/box", targetBackend = TargetBackend.JVM)
            }
        }

        testGroup("compiler/tests-common-new/tests-gen", "compiler/fir/analysis-tests/testData") {
            testClass<AbstractFirDiagnosticTest> {
                model("resolve", excludedPattern = excludedFirTestdataPattern)
            }

            testClass<AbstractFirDiagnosticWithStdLibTest> {
                model("resolveWithStdlib", pattern = TestGeneratorUtil.KT_WITHOUT_DOTS_IN_NAME)
            }
        }
    }
}
