/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import java.io.Serializable

typealias ClasspathArgumentCacheIdType = List<Int>
typealias ClasspathArgumentsType = List<String>
typealias RawCompilerArgumentsBucket = List<String>

interface CompilerArgumentsBucket<T> : Serializable {
    var targetPlatform: T?
    var classpathParts: Pair<T, List<T>>?
    val singleArguments: HashMap<T, T>
    val multipleArguments: HashMap<T, List<T>>
    val flagArguments: ArrayList<T>
    val internalArguments: ArrayList<T>
    val freeArgs: ArrayList<T>
}

class CachedCompilerArgumentsBucket(
    override var targetPlatform: Int? = null,
    override var classpathParts: Pair<Int, List<Int>>? = null,
    override val singleArguments: HashMap<Int, Int> = hashMapOf(),
    override val multipleArguments: HashMap<Int, List<Int>> = hashMapOf(),
    override val flagArguments: ArrayList<Int> = arrayListOf(),
    override val internalArguments: ArrayList<Int> = arrayListOf(),
    override val freeArgs: ArrayList<Int> = arrayListOf()
) : CompilerArgumentsBucket<Int> {
    constructor(otherBucket: CachedCompilerArgumentsBucket) : this() {
        targetPlatform = otherBucket.targetPlatform
        classpathParts = otherBucket.classpathParts
        singleArguments.putAll(otherBucket.singleArguments)
        multipleArguments.putAll(otherBucket.multipleArguments)
        flagArguments.addAll(otherBucket.flagArguments)
        internalArguments.addAll(otherBucket.internalArguments)
        freeArgs.addAll(otherBucket.freeArgs)
    }
}

class FlatCompilerArgumentsBucket(
    override var targetPlatform: String? = null,
    override var classpathParts: Pair<String, List<String>>? = null,
    override val singleArguments: HashMap<String, String> = hashMapOf(),
    override val multipleArguments: HashMap<String, List<String>> = hashMapOf(),
    override val flagArguments: ArrayList<String> = arrayListOf(),
    override val internalArguments: ArrayList<String> = arrayListOf(),
    override val freeArgs: ArrayList<String> = arrayListOf()
) : CompilerArgumentsBucket<String> {
    constructor(otherBucket: FlatCompilerArgumentsBucket) : this() {
        targetPlatform = otherBucket.targetPlatform
        classpathParts = otherBucket.classpathParts
        singleArguments.putAll(otherBucket.singleArguments)
        multipleArguments.putAll(otherBucket.multipleArguments)
        flagArguments.addAll(otherBucket.flagArguments)
        internalArguments.addAll(otherBucket.internalArguments)
        freeArgs.addAll(otherBucket.freeArgs)
    }
}