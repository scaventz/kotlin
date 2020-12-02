/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import java.io.File

interface CompilerArgumentsBucketConverter<From, To> {
    val classLoader: ClassLoader
        get() = CompilerArgumentsBucketConverter::class.java.classLoader

    fun convert(from: From): To
}

class CachedToFlatCompilerArgumentsBucketConverter(val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<CachedCompilerArgumentsBucket, FlatCompilerArgumentsBucket> {

    override fun convert(from: CachedCompilerArgumentsBucket): FlatCompilerArgumentsBucket {
        val flattenTargetPlatform = from.targetPlatform?.let { mapper.getArgument(it) }
        val flattenSingleArguments = from.singleArguments.entries.associate { (k, v) ->
            mapper.getArgument(k) to mapper.getArgument(v)
        }
        val flattenMultipleArguments = from.multipleArguments.entries.associate { (k, v) ->
            mapper.getArgument(k) to v.map { mapper.getArgument(it) }
        }
        val flattenFlagArguments = from.flagArguments.map { mapper.getArgument(it) }
        val flattenClasspathParts = from.classpathParts?.let { (k, v) ->
            mapper.getArgument(k) to v.map { mapper.getArgument(it) }
        }
        val flattenInternalArguments = from.internalArguments.map { mapper.getArgument(it) }
        val flattenFreeArgs = from.freeArgs.map { mapper.getArgument(it) }
        return FlatCompilerArgumentsBucket(flattenTargetPlatform).apply {
            classpathParts = flattenClasspathParts
            singleArguments.putAll(flattenSingleArguments)
            multipleArguments.putAll(flattenMultipleArguments)
            flagArguments.addAll(flattenFlagArguments)
            internalArguments.addAll(flattenInternalArguments)
            freeArgs.addAll(flattenFreeArgs)
        }
    }
}

class FlatToRawCompilerArgumentsBucketConverter :
    CompilerArgumentsBucketConverter<FlatCompilerArgumentsBucket, RawCompilerArgumentsBucket> {

    private val dividedPropertiesWithArgumentAnnotationInfo by lazy {
        DividedPropertiesWithArgumentAnnotationInfoManager(classLoader).dividedPropertiesWithArgumentAnnotationInfo
    }

    override fun convert(from: FlatCompilerArgumentsBucket): RawCompilerArgumentsBucket = mutableListOf<String>().apply {
        from.classpathParts?.also {
            val classpathAnnotationInfo = dividedPropertiesWithArgumentAnnotationInfo.classpathPropertiesToArgumentAnnotation.values.first()
            check(classpathAnnotationInfo.isSuitableValue(it.first)) {
                "Unexpected classpath argument \"$it\"!"
            }
            this@apply.add(it.first)
            this@apply.add(it.second.joinToString(File.pathSeparator))
        }

        val singleArgumentAnnotationInfos = dividedPropertiesWithArgumentAnnotationInfo.singlePropertiesToArgumentAnnotation.values
        from.singleArguments.entries.forEach { (k, v) ->
            val argument = singleArgumentAnnotationInfos.first { it.isSuitableValue(k) }
            if (argument.isAdvanced) {
                this@apply.add("$k=$v")
            } else {
                this@apply.add(k)
                this@apply.add(v)
            }
        }

        val multipleArgumentAnnotationInfos = dividedPropertiesWithArgumentAnnotationInfo.multiplePropertiesToArgumentAnnotation.values
        from.multipleArguments.entries.forEach { (k, v) ->
            val argument = multipleArgumentAnnotationInfos.first { it.isSuitableValue(k) }
            val value = v.joinToString(argument.delimiter)
            if (argument.isAdvanced) {
                this@apply.add("$k=$value")
            } else {
                this@apply.add(k)
                this@apply.add(value)
            }
        }

        addAll(from.flagArguments)
        addAll(from.freeArgs)
        addAll(from.internalArguments)
    }
}

class CachedToRawCompilerArgumentsBucketConverter(val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<CachedCompilerArgumentsBucket, RawCompilerArgumentsBucket> {

    private val cachedToFlatCompilerArgumentsBucketConverter by lazy { CachedToFlatCompilerArgumentsBucketConverter(mapper) }
    private val flatToRawCompilerArgumentsBucketConverter by lazy { FlatToRawCompilerArgumentsBucketConverter() }
    override fun convert(from: CachedCompilerArgumentsBucket): RawCompilerArgumentsBucket =
        cachedToFlatCompilerArgumentsBucketConverter.convert(from).let {
            flatToRawCompilerArgumentsBucketConverter.convert(it)
        }
}

class RawToFlatCompilerArgumentsBucketConverter :
    CompilerArgumentsBucketConverter<RawCompilerArgumentsBucket, FlatCompilerArgumentsBucket> {
    private val dividedPropertiesWithArgumentAnnotationInfo by lazy {
        DividedPropertiesWithArgumentAnnotationInfoManager(classLoader).dividedPropertiesWithArgumentAnnotationInfo
    }

    fun convert(from: RawCompilerArgumentsBucket, serializedTargetPlatform: String?): FlatCompilerArgumentsBucket = convert(from).apply {
        targetPlatform = serializedTargetPlatform
    }

    //TODO forbit usage with @Deprecate out this class
    override fun convert(from: RawCompilerArgumentsBucket): FlatCompilerArgumentsBucket {

        val singlePropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.singlePropertiesToArgumentAnnotation
        val multiplePropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.multiplePropertiesToArgumentAnnotation
        val flagPropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.flagPropertiesToArgumentAnnotation
        val classpathPropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.classpathPropertiesToArgumentAnnotation

        val processedArguments = mutableListOf<String>()

        val classpathArgumentAnnotation = classpathPropertiesToArgumentAnnotation.values.first()
        val flattenClasspathParts = from.indexOfFirst { classpathArgumentAnnotation.isSuitableValue(it) }.takeIf { it != -1 }
            ?.also { processedArguments.add(from[it]); processedArguments.add(from[it + 1]) }
            ?.let { from[it] to from[it + 1].split(File.pathSeparator) }

        fun ArgumentAnnotationInfo.processArgumentWithInfo(): Pair<String, String>? = if (isAdvanced) {
            val found = from.find { isSuitableValue(it) }?.also { processedArguments.add(it) }
            val separate = found?.split('=')?.toTypedArray()
            separate?.let { it[0] to it[1] }
        } else from.indexOfFirst { isSuitableValue(it) }.takeIf { it != -1 }
            ?.also { processedArguments.add(from[it]); processedArguments.add(from[it + 1]) }
            ?.let { from[it] to from[it + 1] }

        val flattenSingleArguments = singlePropertiesToArgumentAnnotation.values.mapNotNull { it.processArgumentWithInfo() }.toMap()
        val flattenMultipleArguments = multiplePropertiesToArgumentAnnotation.values.mapNotNull { info ->
            info.processArgumentWithInfo()?.let { it.first to it.second.split(info.delimiter) }
        }
        val flatFlagArguments =
            flagPropertiesToArgumentAnnotation.values.mapNotNull { info ->
                from.find { info.isSuitableValue(it) }?.also { processedArguments.add(it) }
            }.distinct()

        //TODO replace with InternalArgumentParser.INTERNAL_ARGUMENT_PREFIX via reflection
        val flatInternalArguments = from.filter { it.startsWith("-XX") }.also { processedArguments.addAll(it) }

        val flatFreeArs = from - processedArguments

        return FlatCompilerArgumentsBucket().apply {
            classpathParts = flattenClasspathParts
            singleArguments.putAll(flattenSingleArguments)
            multipleArguments.putAll(flattenMultipleArguments)
            flagArguments.addAll(flatFlagArguments)
            internalArguments.addAll(flatInternalArguments)
            freeArgs.addAll(flatFreeArs)
        }
    }
}

class FlatToCachedCompilerArgumentsBucketConverter(val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<FlatCompilerArgumentsBucket, CachedCompilerArgumentsBucket> {
    override val classLoader: ClassLoader = mapper.javaClass.classLoader
    override fun convert(from: FlatCompilerArgumentsBucket): CachedCompilerArgumentsBucket {
        val cachedTargetPlatform = from.targetPlatform?.let { mapper.cacheArgument(it) }
        val cachedSingleArguments = from.singleArguments.entries.associate {
            mapper.cacheArgument(it.key) to mapper.cacheArgument(it.value)
        }
        val cachedMultipleArguments = from.multipleArguments.entries.associate {
            mapper.cacheArgument(it.key) to it.value.map { v -> mapper.cacheArgument(v) }
        }
        val cachedFlagArguments = from.flagArguments.map { mapper.cacheArgument(it) }
        val cachedClasspathParts =
            from.classpathParts?.let { mapper.cacheArgument(it.first) to it.second.map { v -> mapper.cacheArgument(v) } }

        val cachedInternalArguments = from.internalArguments.map { mapper.cacheArgument(it) }
        val cachedFreeArgs = from.freeArgs.map { mapper.cacheArgument(it) }
        return CachedCompilerArgumentsBucket(cachedTargetPlatform).apply {
            classpathParts = cachedClasspathParts
            singleArguments.putAll(cachedSingleArguments)
            multipleArguments.putAll(cachedMultipleArguments)
            flagArguments.addAll(cachedFlagArguments)
            internalArguments.addAll(cachedInternalArguments)
            freeArgs.addAll(cachedFreeArgs)
        }
    }
}

class RawToCachedCompilerArgumentsBucketConverter(val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<RawCompilerArgumentsBucket, CachedCompilerArgumentsBucket> {
    override val classLoader: ClassLoader = mapper.javaClass.classLoader
    private val rawToFlatCompilerArgumentsBucketConverter by lazy { RawToFlatCompilerArgumentsBucketConverter() }
    private val flatToCachedCompilerArgumentsBucketConverter by lazy { FlatToCachedCompilerArgumentsBucketConverter(mapper) }

    fun convert(from: RawCompilerArgumentsBucket, serializedTargetPlatform: String?): CachedCompilerArgumentsBucket =
        rawToFlatCompilerArgumentsBucketConverter.convert(from, serializedTargetPlatform).let {
            flatToCachedCompilerArgumentsBucketConverter.convert(it)
        }

    override fun convert(from: RawCompilerArgumentsBucket): CachedCompilerArgumentsBucket =
        rawToFlatCompilerArgumentsBucketConverter.convert(from).let {
            flatToCachedCompilerArgumentsBucketConverter.convert(it)
        }
}
