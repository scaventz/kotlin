/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.caching.*
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.platform.IdePlatformKind
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.io.File
import kotlin.reflect.KProperty1

private fun CommonCompilerArguments.calculateTargetPlatform() = IdePlatformKind.platformByCompilerArguments(this)?.serializeComponentPlatforms()

fun CommonCompilerArguments.toFlatCompilerArguments(): FlatCompilerArgumentsBucket =
    ArgumentUtils.convertArgumentsToStringList(this).let {
        RawToFlatCompilerArgumentsBucketConverter(this::class.java.classLoader).convert(it, calculateTargetPlatform())
    }

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

@Suppress("UNCHECKED_CAST")
fun mergeFlatCompilerArgumentsBuckets(from: FlatCompilerArgumentsBucket, to: FlatCompilerArgumentsBucket) {
    to.targetPlatform = from.targetPlatform

    if (from.classpathParts != null || to.classpathParts != null) {
        val joinedClasspathParts = (from.classpathParts?.second.orEmpty() + to.classpathParts?.second.orEmpty()).distinct()
        val classpathKey = to.classpathParts?.first ?: from.classpathParts?.first
        to.classpathParts = classpathKey?.let { it to joinedClasspathParts }
    }

    with(DividedPropertiesWithArgumentAnnotationInfoManager(to::class.java.classLoader).dividedPropertiesWithArgumentAnnotationInfo) {
        singlePropertiesToArgumentAnnotation.keys.mapNotNull { it as? KProperty1<CommonCompilerArguments, String?> }
            .associateWith { from.extractSingleArgumentValue(it) }
            .forEach { (prop, fromValue) -> to.setSingleArgument(prop, fromValue) }
        multiplePropertiesToArgumentAnnotation.keys.mapNotNull { it as? KProperty1<CommonCompilerArguments, Array<String>?> }
            .associateWith { from.extractMultipleArgumentValue(it) }
            .forEach { (prop, fromValue) -> to.setMultipleArgument(prop, fromValue) }
        flagPropertiesToArgumentAnnotation.keys.mapNotNull { it as? KProperty1<CommonCompilerArguments, Boolean> }
            .associateWith { from.extractFlagArgumentValue(it) }
            .forEach { (prop, fromValue) -> to.setFlagArgument(prop, fromValue) }
    }
    to.freeArgs.apply {
        clear()
        addAll(from.freeArgs)
    }
    to.internalArguments.apply {
        clear()
        addAll(from.internalArguments)
    }
}
