/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.caching.ArgumentAnnotationInfo
import org.jetbrains.kotlin.caching.FlatCompilerArgumentsBucket
import org.jetbrains.kotlin.caching.RawToFlatCompilerArgumentsBucketConverter
import org.jetbrains.kotlin.caching.isSuitableValue
import org.jetbrains.kotlin.cli.common.arguments.Argument
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
import org.jetbrains.kotlin.cli.common.arguments.isAdvanced
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.konan.file.File
import kotlin.reflect.KProperty1

fun createDummyCompilerArgumentsBucket(): FlatCompilerArgumentsBucket = CommonCompilerArguments.DummyImpl()
    .let { ArgumentUtils.convertArgumentsToStringList(it) }
    .let { RawToFlatCompilerArgumentsBucketConverter().convert(it) }

inline fun <reified T : CommonToolArguments, reified R> KProperty1<T, R>.calculateArgumentAnnotation(): ArgumentAnnotationInfo? {
    val argumentAnnotation = annotations.firstOrNull { it is Argument } as? Argument ?: return null
    return with(argumentAnnotation) { ArgumentAnnotationInfo(value, shortName, deprecatedName, delimiter, isAdvanced) }
}

inline fun <reified T : CommonToolArguments> FlatCompilerArgumentsBucket.extractGeneralArgument(
    property: KProperty1<T, String?>
): Pair<String, String>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return singleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }?.toPair()
}

inline fun <reified T : CommonToolArguments> FlatCompilerArgumentsBucket.setGeneralArgument(
    property: KProperty1<T, String?>,
    newValue: String?
) {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return
    val oldValue = extractGeneralArgument(property)
    if (newValue == oldValue?.second) return
    singleArguments.remove(oldValue?.first)
    newValue?.also { singleArguments[argumentAnnotationInfo.value] = it }
}

inline fun <reified T : CommonToolArguments> FlatCompilerArgumentsBucket.extractMultipleArgument(
    property: KProperty1<T, Array<String>?>
): Pair<String, Array<String>>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return multipleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }
        ?.let { it.key to it.value.toTypedArray() }
}

inline fun <reified T : CommonToolArguments> FlatCompilerArgumentsBucket.setMultipleArgument(
    property: KProperty1<T, Array<String>?>,
    newValue: Array<String>?
) {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return
    val oldValue = extractMultipleArgument(property)
    if (newValue.contentEquals(oldValue?.second)) return
    multipleArguments.remove(oldValue?.first)
    newValue?.also { multipleArguments[argumentAnnotationInfo.value] = it.toList() }
}

inline fun <reified T : CommonToolArguments> FlatCompilerArgumentsBucket.extractFlagArgument(
    property: KProperty1<T, Boolean>
): Pair<String, Boolean> {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation()!!
    return flagArguments.firstOrNull { argumentAnnotationInfo.isSuitableValue(it) }?.let { it to true }
        ?: argumentAnnotationInfo.value to false
}

inline fun <reified T : CommonToolArguments> FlatCompilerArgumentsBucket.setFlagArgument(
    property: KProperty1<T, Boolean>,
    newValue: Boolean
) {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return
    val oldValue = extractFlagArgument(property)
    if (newValue == oldValue.second) return
    flagArguments.remove(oldValue.first)
    if (newValue) flagArguments.add(argumentAnnotationInfo.value)
}

val FlatCompilerArgumentsBucket.extractClasspathArgument: String?
    get() = classpathParts?.second?.joinToString(File.pathSeparator)

