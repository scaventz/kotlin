/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.services

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule

abstract class KotlinCoreEnvironmentProvider : TestService {
    abstract val testRootDisposable: Disposable

    abstract fun getKotlinCoreEnvironment(module: TestModule): KotlinCoreEnvironment

    fun getCompilerConfiguration(module: TestModule): CompilerConfiguration {
        return getKotlinCoreEnvironment(module).configuration
    }
}

val TestServices.kotlinCoreEnvironmentProvider: KotlinCoreEnvironmentProvider by TestServices.testServiceAccessor()

class KotlinCoreEnvironmentProviderImpl(
    override val testRootDisposable: Disposable,
    val configurators: List<EnvironmentConfigurator>
) : KotlinCoreEnvironmentProvider() {
    private val cache: MutableMap<TestModule, KotlinCoreEnvironment> = mutableMapOf()

    override fun getKotlinCoreEnvironment(module: TestModule): KotlinCoreEnvironment {
        return cache.getOrPut(module) {
            KotlinCoreEnvironment.createForTests(
                testRootDisposable,
                createCompilerConfiguration(module),
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            ).apply {
                configurators.forEach { it.configureEnvironment(this, module) }
            }
        }
    }

    private fun createCompilerConfiguration(module: TestModule): CompilerConfiguration {
        val configuration = CompilerConfiguration()
        configuration[CommonConfigurationKeys.MODULE_NAME] = module.name

        configuration[CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY] = object : MessageCollector {
            override fun clear() {}

            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity == CompilerMessageSeverity.ERROR) {
                    val prefix = if (location == null) "" else "(" + location.path + ":" + location.line + ":" + location.column + ") "
                    throw AssertionError(prefix + message)
                }
            }

            override fun hasErrors(): Boolean = false
        }
        configuration.languageVersionSettings = module.languageVersionSettings

        configurators.forEach { it.configureCompilerConfiguration(configuration, module) }

        return configuration
    }

    private operator fun <T : Any> CompilerConfiguration.set(key: CompilerConfigurationKey<T>, value: T) {
        put(key, value)
    }
}

val TestModule.javaFiles: List<TestFile>
    get() = files.filter { it.isJavaFile }
