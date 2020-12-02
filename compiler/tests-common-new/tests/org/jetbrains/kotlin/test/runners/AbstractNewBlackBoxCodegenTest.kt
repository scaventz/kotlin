/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.runners

import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.backend.classic.ClassicJvmBackendFacade
import org.jetbrains.kotlin.test.backend.handlers.JvmBoxRunner
import org.jetbrains.kotlin.test.backend.ir.JvmIrBackendFacade
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontend2ClassicBackendConverter
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontend2IrConverter
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontendFacade
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.JUnit5Assertions
import org.jetbrains.kotlin.test.services.configuration.JvmEnvironmentConfigurator

abstract class AbstractNewBlackBoxCodegenTest : AbstractKotlinCompilerTest() {
    open val targetBackend: BackendKind<*>
        get() = BackendKinds.ClassicBackend

    override fun TestConfigurationBuilder.configuration() {
        globalDefaults {
            frontend = FrontendKinds.ClassicFrontend
            backend = targetBackend
            targetPlatform = JvmPlatforms.defaultJvmPlatform
            dependencyKind = DependencyKind.Binary
        }

        assertions = JUnit5Assertions

        useConfigurators(::JvmEnvironmentConfigurator)

        useFrontendFacades(::ClassicFrontendFacade)
//        useFrontendHandlers(::ClassicDiagnosticsHandler)
        useFrontend2BackendConverters(::ClassicFrontend2ClassicBackendConverter, ::ClassicFrontend2IrConverter)
        useBackendFacades(::ClassicJvmBackendFacade, ::JvmIrBackendFacade)
        useArtifactsHandlers(::JvmBoxRunner)
        useMetaTestConfigurators(::BlackBoxCodegenSuppressor)
    }
}
