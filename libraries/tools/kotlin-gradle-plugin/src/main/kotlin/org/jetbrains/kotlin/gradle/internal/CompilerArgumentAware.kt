/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.internal

import com.intellij.util.text.VersionComparatorUtil
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.serializeComponentPlatforms
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.impl.FakeK2NativeCompilerArguments
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatforms

interface CompilerArgumentAware<T : CommonToolArguments> {
    val serializedCompilerArguments: List<String>
        get() = ArgumentUtils.convertArgumentsToStringList(prepareCompilerArguments())

    val serializedCompilerArgumentsIgnoreClasspathIssues: List<String>
        get() = ArgumentUtils.convertArgumentsToStringList(prepareCompilerArguments(ignoreClasspathResolutionErrors = true))

    val defaultSerializedCompilerArguments: List<String>
        get() = createCompilerArgs()
            .also { setupCompilerArgs(it, defaultsOnly = true) }
            .let(ArgumentUtils::convertArgumentsToStringList)

    val filteredArgumentsMap: Map<String, String>
        get() = CompilerArgumentsGradleInput.createInputsMap(prepareCompilerArguments())

    val serializedTargetPlatform: String?
        get() = calculateTargetPlatform(prepareCompilerArguments())?.serializeComponentPlatforms()

    fun createCompilerArgs(): T
    fun setupCompilerArgs(args: T, defaultsOnly: Boolean = false, ignoreClasspathResolutionErrors: Boolean = false)
}

internal fun <T : CommonToolArguments> CompilerArgumentAware<T>.prepareCompilerArguments(ignoreClasspathResolutionErrors: Boolean = false) =
    createCompilerArgs().also { setupCompilerArgs(it, ignoreClasspathResolutionErrors = ignoreClasspathResolutionErrors) }

private fun calculateTargetPlatform(arguments: CommonToolArguments) = when (arguments) {
    is K2JVMCompilerArguments -> arguments.jvmTarget?.let { target ->
        JvmTarget.values().firstOrNull { VersionComparatorUtil.COMPARATOR.compare(it.description, target) >= 0 }
            ?.let { JvmPlatforms.jvmPlatformByTargetVersion(it) }
            ?: JvmPlatforms.defaultJvmPlatform
    } ?: JvmPlatforms.defaultJvmPlatform
    is K2JSCompilerArguments -> JsPlatforms.defaultJsPlatform
    is FakeK2NativeCompilerArguments -> NativePlatforms.unspecifiedNativePlatform
    is K2MetadataCompilerArguments -> CommonPlatforms.defaultCommonPlatform
    else -> null
}

interface CompilerArgumentAwareWithInput<T : CommonToolArguments> : CompilerArgumentAware<T> {
    @get:Internal
    override val serializedCompilerArguments: List<String>
        get() = super.serializedCompilerArguments

    @get:Internal
    override val defaultSerializedCompilerArguments: List<String>
        get() = super.defaultSerializedCompilerArguments

    @get:Internal
    override val serializedCompilerArgumentsIgnoreClasspathIssues: List<String>
        get() = super.serializedCompilerArgumentsIgnoreClasspathIssues

    @get:Internal
    override val serializedTargetPlatform: String?
        get() = super.serializedTargetPlatform

    @get:Input
    override val filteredArgumentsMap: Map<String, String>
        get() = super.filteredArgumentsMap
}