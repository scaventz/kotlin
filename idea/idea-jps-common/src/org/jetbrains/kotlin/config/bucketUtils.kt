/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.caching.ArgumentAnnotationInfo
import org.jetbrains.kotlin.caching.FlatCompilerArgumentsBucket
import org.jetbrains.kotlin.caching.RawToFlatCompilerArgumentsBucketConverter
import org.jetbrains.kotlin.caching.isSuitableValue
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.io.File
import kotlin.reflect.KProperty1

fun CommonCompilerArguments.toFlatCompilerArguments(): FlatCompilerArgumentsBucket =
    ArgumentUtils.convertArgumentsToStringList(this).let { RawToFlatCompilerArgumentsBucketConverter().convert(it) }

fun createDummyCompilerArgumentsBucket(): FlatCompilerArgumentsBucket = CommonCompilerArguments.DummyImpl().toFlatCompilerArguments()

fun <T : CommonToolArguments, R> KProperty1<T, R>.calculateArgumentAnnotation(): ArgumentAnnotationInfo? {
    val argumentAnnotation = annotations.firstOrNull { it is Argument } as? Argument ?: return null
    return with(argumentAnnotation) { ArgumentAnnotationInfo(value, shortName, deprecatedName, delimiter, isAdvanced) }
}

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.extractSingleArgument(
    property: KProperty1<T, String?>
): Pair<String, String>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return singleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }?.toPair()
}

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.extractSingleArgumentValue(
    property: KProperty1<T, String?>
): String? = extractSingleArgument(property)?.second

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.setSingleArgument(
    property: KProperty1<T, String?>,
    newValue: String?
) {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return
    val oldValue = extractSingleArgument(property)
    if (newValue == oldValue?.second) return
    singleArguments.remove(oldValue?.first)
    newValue?.also { singleArguments[argumentAnnotationInfo.value] = it }
}

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.extractMultipleArgument(
    property: KProperty1<T, Array<String>?>
): Pair<String, Array<String>>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return multipleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }
        ?.let { it.key to it.value.toTypedArray() }
}

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.extractMultipleArgumentValue(
    property: KProperty1<T, Array<String>?>
): Array<String>? = extractMultipleArgument(property)?.second

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.setMultipleArgument(
    property: KProperty1<T, Array<String>?>,
    newValue: Array<String>?
) {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return
    val oldValue = extractMultipleArgument(property)
    if (newValue.contentEquals(oldValue?.second)) return
    multipleArguments.remove(oldValue?.first)
    newValue?.also { multipleArguments[argumentAnnotationInfo.value] = it.toList() }
}

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.extractFlagArgument(
    property: KProperty1<T, Boolean>
): Pair<String, Boolean> {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation()!!
    return flagArguments.firstOrNull { argumentAnnotationInfo.isSuitableValue(it) }?.let { it to true }
        ?: argumentAnnotationInfo.value to false
}

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.extractFlagArgumentValue(
    property: KProperty1<T, Boolean>
): Boolean = extractFlagArgument(property).second

fun <T : CommonToolArguments> FlatCompilerArgumentsBucket.setFlagArgument(
    property: KProperty1<T, Boolean>,
    newValue: Boolean
) {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return
    val oldValue = extractFlagArgument(property)
    if (newValue == oldValue.second) return
    flagArguments.remove(oldValue.first)
    if (newValue) flagArguments.add(argumentAnnotationInfo.value)
}

fun FlatCompilerArgumentsBucket.extractClasspaths(): List<String>? = classpathParts?.second

fun FlatCompilerArgumentsBucket.extractClasspathJoined(): String? = classpathParts?.second?.joinToString(File.pathSeparator)

fun FlatCompilerArgumentsBucket.setClasspathArgument(newClasspaths: List<String>) {
    classpathParts = classpathParts?.let {
        it.first to newClasspaths
    } ?: newClasspaths.ifNotEmpty { K2JVMCompilerArguments::classpath.calculateArgumentAnnotation()!!.value to this }
}
