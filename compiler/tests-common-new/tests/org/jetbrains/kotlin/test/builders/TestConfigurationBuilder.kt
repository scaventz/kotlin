/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.builders

import org.jetbrains.kotlin.test.TestConfiguration
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.impl.TestConfigurationImpl
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.*

typealias Constructor<T> = (TestServices) -> T

@DefaultsDsl
class TestConfigurationBuilder {
    val defaultsProviderBuilder: DefaultsProviderBuilder = DefaultsProviderBuilder()
    lateinit var assertions: Assertions

    private val facades: MutableList<Constructor<AbstractTestFacade<*, *>>> = mutableListOf()

    private val handlers: MutableList<Constructor<AnalysisHandler<*>>> = mutableListOf()

    private val sourcePreprocessors: MutableList<Constructor<SourceFilePreprocessor>> = mutableListOf()
    private val additionalMetaInfoProcessors: MutableList<Constructor<AdditionalMetaInfoProcessor>> = mutableListOf()
    private val environmentConfigurators: MutableList<Constructor<EnvironmentConfigurator>> = mutableListOf()

    private val additionalSourceProviders: MutableList<Constructor<AdditionalSourceProvider>> = mutableListOf()

    private val metaTestConfigurators: MutableList<Constructor<MetaTestConfigurator>> = mutableListOf()

    private var metaInfoHandlerEnabled: Boolean = false

    private val directives: MutableList<DirectivesContainer> = mutableListOf()
    val defaultRegisteredDirectivesBuilder: RegisteredDirectivesBuilder = RegisteredDirectivesBuilder()

    inline fun globalDefaults(init: DefaultsProviderBuilder.() -> Unit) {
        defaultsProviderBuilder.apply(init)
    }

    fun useFrontendFacades(vararg constructor: Constructor<FrontendFacade<*>>) {
        facades += constructor
    }

    fun useBackendFacades(vararg constructor: Constructor<BackendFacade<*, *>>) {
        facades += constructor
    }

    fun useFrontend2BackendConverters(vararg constructor: Constructor<Frontend2BackendConverter<*, *>>) {
        facades += constructor
    }

    fun useFrontendHandlers(vararg constructor: Constructor<FrontendOutputHandler<*>>) {
        handlers += constructor
    }

    fun useBackendHandlers(vararg constructor: Constructor<BackendInputHandler<*>>) {
        handlers += constructor
    }

    fun useArtifactsHandlers(vararg constructor: Constructor<BinaryArtifactHandler<*>>) {
        handlers += constructor
    }

    fun useSourcePreprocessor(vararg preprocessors: Constructor<SourceFilePreprocessor>) {
        sourcePreprocessors += preprocessors
    }

    fun useDirectives(vararg directives: DirectivesContainer) {
        this.directives += directives
    }

    fun useConfigurators(vararg environmentConfigurators: Constructor<EnvironmentConfigurator>) {
        this.environmentConfigurators += environmentConfigurators
    }

    fun useMetaInfoProcessors(vararg updaters: Constructor<AdditionalMetaInfoProcessor>) {
        additionalMetaInfoProcessors += updaters
    }

    fun useAdditionalSourceProviders(vararg providers: Constructor<AdditionalSourceProvider>) {
        additionalSourceProviders += providers
    }

    fun useMetaTestConfigurators(vararg configurators: Constructor<MetaTestConfigurator>) {
        metaTestConfigurators += configurators
    }

    inline fun defaultDirectives(init: RegisteredDirectivesBuilder.() -> Unit) {
        defaultRegisteredDirectivesBuilder.apply(init)
    }

    fun enableMetaInfoHandler() {
        metaInfoHandlerEnabled = true
    }

    fun build(): TestConfiguration {
        return TestConfigurationImpl(
            defaultsProviderBuilder.build(),
            assertions,
            facades,
            handlers,
            sourcePreprocessors,
            additionalMetaInfoProcessors,
            environmentConfigurators,
            additionalSourceProviders,
            metaTestConfigurators,
            metaInfoHandlerEnabled,
            directives,
            defaultRegisteredDirectivesBuilder.build()
        )
    }
}

inline fun testConfiguration(init: TestConfigurationBuilder.() -> Unit): TestConfiguration {
    return TestConfigurationBuilder().apply(init).build()
}
