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

package org.jetbrains.kotlin.idea.project

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.caching.FlatCompilerArgumentsBucket
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.compiler.IDELanguageSettingsProvider
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.facet.getLibraryLanguageLevel
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.*
import org.jetbrains.kotlin.platform.impl.isCommon
import org.jetbrains.kotlin.platform.js.isJs
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.kotlin.utils.JavaTypeEnhancementState
import java.io.File

val KtElement.platform: TargetPlatform
    get() = TargetPlatformDetector.getPlatform(containingKtFile)

val KtElement.builtIns: KotlinBuiltIns
    get() = getResolutionFacade().moduleDescriptor.builtIns

var KtFile.forcedTargetPlatform: TargetPlatform? by UserDataProperty(Key.create("FORCED_TARGET_PLATFORM"))

fun Module.getAndCacheLanguageLevelByDependencies(): LanguageVersion {
    val facetSettings = KotlinFacetSettingsProvider.getInstance(project)?.getInitializedSettings(this)
    val languageLevel = getLibraryLanguageLevel(this, null, facetSettings?.targetPlatform?.idePlatformKind)

    // Preserve inferred version in facet/project settings
    if (facetSettings == null || facetSettings.useProjectSettings) {
        KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
            if (languageVersion == null) {
                languageVersion = languageLevel.versionString
            }
            if (apiVersion == null) {
                apiVersion = languageLevel.versionString
            }
        }
    } else {
        with(facetSettings) {
            if (this.languageLevel == null) {
                this.languageLevel = languageLevel
            }
            if (this.apiLevel == null) {
                this.apiLevel = languageLevel
            }
        }
    }

    return languageLevel
}

/**
 * Returns stable binary name of module from the *Kotlin* point of view.
 * Having correct module name is critical for compiler, e.g. for 'internal'-visibility
 * mangling (see KT-23668).
 *
 * Note that build systems and IDEA have their own module systems and, potentially, their
 * names can be different from Kotlin module name (though this is the rare case).
 */
fun Module.getStableName(): Name {
    // Here we check ideal situation: we have a facet, and it has 'moduleName' argument.
    // This should be the case for the most environments
    val settingsProvider = KotlinFacetSettingsProvider.getInstance(project)
    val settings = settingsProvider?.getInitializedSettings(this)
    val targetPlatform = settings?.targetPlatform ?: TargetPlatformDetector.getPlatform(this)
    val bucket = settings?.compilerArgumentsBucket
    val explicitNameFromArguments = when {
        targetPlatform.isJvm() -> bucket?.extractSingleArgumentValue(K2JVMCompilerArguments::moduleName)
        targetPlatform.isJs() -> bucket?.extractSingleArgumentValue(K2JSCompilerArguments::outputFile)
            ?.let { FileUtil.getNameWithoutExtension(File(it)) }
        targetPlatform.isCommon() -> bucket?.extractSingleArgumentValue(K2MetadataCompilerArguments::moduleName)
        else -> null // Actually, only 'null' possible here
    }

    // Here we handle pessimistic case: no facet is found or it declares no 'moduleName'
    // We heuristically assume that name of Module in IDEA is the same as Kotlin module (which may be not the case)
    val stableNameApproximation = explicitNameFromArguments ?: name

    return Name.special("<$stableNameApproximation>")
}

@JvmOverloads
fun Project.getLanguageVersionSettings(
    contextModule: Module? = null,
    javaTypeEnhancementState: JavaTypeEnhancementState? = null,
    isReleaseCoroutines: Boolean? = null
): LanguageVersionSettings {
    val kotlinFacetSettings = contextModule?.let {
        KotlinFacetSettingsProvider.getInstance(this)?.getInitializedSettings(it)
    }

    val targetPlatform = kotlinFacetSettings?.targetPlatform
        ?: contextModule?.let { TargetPlatformDetector.getPlatform(it) }
        ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform

    val bucket = kotlinFacetSettings?.compilerArgumentsBucket
        ?: KotlinCommonCompilerArgumentsHolder.getInstance(this).settings.toFlatCompilerArguments()
    val languageVersion =
        kotlinFacetSettings?.languageLevel
            ?: LanguageVersion.fromVersionString(bucket.extractSingleArgumentValue(CommonCompilerArguments::languageVersion))
            ?: contextModule?.getAndCacheLanguageLevelByDependencies()
            ?: VersionView.RELEASED_VERSION
    val apiVersion = ApiVersion.createByLanguageVersion(
        LanguageVersion.fromVersionString(bucket.extractSingleArgumentValue(CommonCompilerArguments::apiVersion)) ?: languageVersion
    )
    val compilerSettings = KotlinCompilerSettings.getInstance(this).settings

    val additionalArguments: CommonCompilerArguments = parseArguments(
        targetPlatform,
        compilerSettings.additionalArgumentsAsList
    )

    val extraLanguageFeatures = additionalArguments.configureLanguageFeatures(MessageCollector.NONE).apply {
        configureCoroutinesSupport(
            CoroutineSupport.byCompilerArguments(KotlinCommonCompilerArgumentsHolder.getInstance(this@getLanguageVersionSettings).settings),
            languageVersion
        )
        if (isReleaseCoroutines != null) {
            put(
                LanguageFeature.ReleaseCoroutines,
                if (isReleaseCoroutines) LanguageFeature.State.ENABLED else LanguageFeature.State.DISABLED
            )
        }
    }

    val extraAnalysisFlags = additionalArguments.configureAnalysisFlags(MessageCollector.NONE).apply {
        if (javaTypeEnhancementState != null) put(JvmAnalysisFlags.javaTypeEnhancementState, javaTypeEnhancementState)
        initIDESpecificAnalysisSettings(this@getLanguageVersionSettings)
    }

    return LanguageVersionSettingsImpl(
        languageVersion, apiVersion,
        bucket.configureAnalysisFlags(targetPlatform, MessageCollector.NONE) + extraAnalysisFlags,
        bucket.configureLanguageFeatures(targetPlatform, MessageCollector.NONE) + extraLanguageFeatures
    )
}

private val LANGUAGE_VERSION_SETTINGS = Key.create<CachedValue<LanguageVersionSettings>>("LANGUAGE_VERSION_SETTINGS")

val Module.languageVersionSettings: LanguageVersionSettings
    get() {
        val cachedValue =
            getUserData(LANGUAGE_VERSION_SETTINGS)
                ?: createCachedValueForLanguageVersionSettings().also { putUserData(LANGUAGE_VERSION_SETTINGS, it) }

        return cachedValue.value
    }

@TestOnly
fun Module.withLanguageVersionSettings(value: LanguageVersionSettings, body: () -> Unit) {
    val previousLanguageVersionSettings = getUserData(LANGUAGE_VERSION_SETTINGS)
    try {
        putUserData(
            LANGUAGE_VERSION_SETTINGS,
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result(
                        value, ProjectRootModificationTracker.getInstance(project)
                    )
                },
                false
            )
        )

        body()
    } finally {
        putUserData(LANGUAGE_VERSION_SETTINGS, previousLanguageVersionSettings)
    }
}

private fun Module.createCachedValueForLanguageVersionSettings(): CachedValue<LanguageVersionSettings> {
    return CachedValuesManager.getManager(project).createCachedValue({
                                                                         CachedValueProvider.Result(
                                                                             computeLanguageVersionSettings(),
                                                                             ProjectRootModificationTracker.getInstance(
                                                                                 project
                                                                             )
                                                                         )
                                                                     }, false)
}

private fun Module.shouldUseProjectLanguageVersionSettings(): Boolean {
    val facetSettingsProvider = KotlinFacetSettingsProvider.getInstance(project) ?: return true
    return facetSettingsProvider.getSettings(this) == null || facetSettingsProvider.getInitializedSettings(this).useProjectSettings
}

private fun Module.computeLanguageVersionSettings(): LanguageVersionSettings {
    if (shouldUseProjectLanguageVersionSettings()) return project.getLanguageVersionSettings()

    val facetSettings = KotlinFacetSettingsProvider.getInstance(project)?.getInitializedSettings(this)


    val languageVersion: LanguageVersion
    val apiVersion: LanguageVersion

    if (facetSettings != null) {
        languageVersion = facetSettings.languageLevel ?: getAndCacheLanguageLevelByDependencies()
        apiVersion = facetSettings.apiLevel ?: languageVersion
    } else {
        languageVersion = getAndCacheLanguageLevelByDependencies()
        apiVersion = languageVersion
    }

    val targetPlatform = facetSettings?.targetPlatform ?: TargetPlatformDetector.getPlatform(this)

    val languageFeatures = facetSettings?.compilerArgumentsBucket?.configureLanguageFeatures(targetPlatform, MessageCollector.NONE)?.apply {
        configureCoroutinesSupport(facetSettings.coroutineSupport, languageVersion)
        configureMultiplatformSupport(facetSettings.targetPlatform?.idePlatformKind, this@computeLanguageVersionSettings)
    }.orEmpty()

    val analysisFlags = facetSettings
        ?.compilerArgumentsBucket
        ?.configureAnalysisFlags(targetPlatform, MessageCollector.NONE)
        ?.apply { initIDESpecificAnalysisSettings(project) }
        .orEmpty()

    return LanguageVersionSettingsImpl(
        languageVersion,
        ApiVersion.createByLanguageVersion(apiVersion),
        analysisFlags,
        languageFeatures
    )
}

private fun MutableMap<AnalysisFlag<*>, Any>.initIDESpecificAnalysisSettings(project: Project) {
    if (KotlinMultiplatformAnalysisModeComponent.getMode(project) == KotlinMultiplatformAnalysisModeComponent.Mode.COMPOSITE) {
        put(AnalysisFlags.useTypeRefinement, true)
    }
    if (KotlinLibraryToSourceAnalysisComponent.isEnabled(project)) {
        put(AnalysisFlags.libraryToSourceAnalysis, true)
    }
    put(AnalysisFlags.ideMode, true)
}

val Module.platform: TargetPlatform?
    get() = KotlinFacetSettingsProvider.getInstance(project)?.getInitializedSettings(this)?.targetPlatform ?: project.platform

val Module.isHMPPEnabled: Boolean
    get() = KotlinFacetSettingsProvider.getInstance(project)?.getInitializedSettings(this)?.isHmppEnabled ?: false

// FIXME(dsavvinov): this logic is clearly wrong in MPP environment; review and fix
val Project.platform: TargetPlatform?
    get() {
        val jvmTarget = Kotlin2JvmCompilerArgumentsHolder.getInstance(this).settings.jvmTarget ?: return null
        val version = JvmTarget.fromString(jvmTarget) ?: return null
        return JvmPlatforms.jvmPlatformByTargetVersion(version)
    }

private val Module.implementsCommonModule: Boolean
    get() = !platform.isCommon() // FIXME(dsavvinov): this doesn't seems right, in multilevel-MPP 'common' modules can implement other commons
            && ModuleRootManager.getInstance(this).dependencies.any { it.platform.isCommon() }

private fun parseArguments(
    platformKind: TargetPlatform,
    additionalArguments: List<String>
): CommonCompilerArguments {
    return platformKind.createArguments { parseCommandLineArguments(additionalArguments, this) }
}

fun MutableMap<LanguageFeature, LanguageFeature.State>.configureCoroutinesSupport(
    coroutineSupport: LanguageFeature.State?,
    languageVersion: LanguageVersion
) {
    val state = if (languageVersion >= LanguageVersion.KOTLIN_1_3) {
        LanguageFeature.State.ENABLED
    } else {
        coroutineSupport ?: LanguageFeature.Coroutines.defaultState
    }
    put(LanguageFeature.Coroutines, state)
}

fun MutableMap<LanguageFeature, LanguageFeature.State>.configureMultiplatformSupport(
    platformKind: IdePlatformKind<*>?,
    module: Module?
) {
    if (platformKind.isCommon || module?.implementsCommonModule == true) {
        put(LanguageFeature.MultiPlatformProjects, LanguageFeature.State.ENABLED)
    }
}

val PsiElement.languageVersionSettings: LanguageVersionSettings
    get() {
        if (ServiceManager.getService(project, ProjectFileIndex::class.java) == null) {
            return LanguageVersionSettingsImpl.DEFAULT
        }
        return IDELanguageSettingsProvider.getLanguageVersionSettings(this.getModuleInfo(), project)
    }

fun FlatCompilerArgumentsBucket.configureAnalysisFlags(
    targetPlatform: TargetPlatform,
    collector: MessageCollector
): MutableMap<AnalysisFlag<*>, Any> =
    HashMap<AnalysisFlag<*>, Any>().apply {
        put(AnalysisFlags.skipMetadataVersionCheck, extractFlagArgumentValue(CommonCompilerArguments::skipMetadataVersionCheck))
        put(
            AnalysisFlags.skipPrereleaseCheck,
            extractFlagArgumentValue(CommonCompilerArguments::skipPrereleaseCheck)
                    || (extractFlagArgumentValue(CommonCompilerArguments::skipMetadataVersionCheck))
        )
        put(AnalysisFlags.multiPlatformDoNotCheckActual, extractFlagArgumentValue(CommonCompilerArguments::noCheckActual))
        val experimentalFqNames = extractMultipleArgumentValue(CommonCompilerArguments::experimental)?.toList().orEmpty()
        if (experimentalFqNames.isNotEmpty()) {
            put(AnalysisFlags.experimental, experimentalFqNames)
            collector.report(CompilerMessageSeverity.WARNING, "'-Xexperimental' is deprecated and will be removed in a future release")
        }
        put(
            AnalysisFlags.useExperimental, extractMultipleArgumentValue(CommonCompilerArguments::useExperimental)?.toList().orEmpty()
                    + extractMultipleArgumentValue(CommonCompilerArguments::optIn)?.toList().orEmpty()
        )
        put(AnalysisFlags.expectActualLinker, extractFlagArgumentValue(CommonCompilerArguments::expectActualLinker))
        put(AnalysisFlags.explicitApiVersion, extractSingleArgumentValue(CommonCompilerArguments::apiVersion) != null)
        put(AnalysisFlags.allowResultReturnType, extractFlagArgumentValue(CommonCompilerArguments::allowResultReturnType))
        extractSingleArgumentValue(CommonCompilerArguments::explicitApi)?.apply {
            ExplicitApiMode.fromString(this)?.also { put(AnalysisFlags.explicitApiMode, it) } ?: collector.report(
                CompilerMessageSeverity.ERROR,
                "Unknown value for parameter -Xexplicit-api: '$this'. Value should be one of ${ExplicitApiMode.availableValues()}"
            )
        }
        if (targetPlatform.isJvm()) {
            put(
                JvmAnalysisFlags.strictMetadataVersionSemantics,
                extractFlagArgumentValue(K2JVMCompilerArguments::strictMetadataVersionSemantics)
            )
            put(
                JvmAnalysisFlags.javaTypeEnhancementState, JavaTypeEnhancementStateParser(collector).parse(
                    extractMultipleArgumentValue(K2JVMCompilerArguments::jsr305),
                    extractSingleArgumentValue(K2JVMCompilerArguments::supportCompatqualCheckerFrameworkAnnotations),
                    extractSingleArgumentValue(K2JVMCompilerArguments::jspecifyAnnotations)
                )
            )
            put(
                AnalysisFlags.ignoreDataFlowInAssert,
                JVMAssertionsMode.fromString(extractSingleArgumentValue(K2JVMCompilerArguments::assertionsMode)) != JVMAssertionsMode.LEGACY
            )
            extractSingleArgumentValue(K2JVMCompilerArguments::jvmDefault).apply {
                JvmDefaultMode.fromStringOrNull(this)?.let {
                    put(JvmAnalysisFlags.jvmDefaultMode, it)
                } ?: collector.report(
                    CompilerMessageSeverity.ERROR,
                    "Unknown @JvmDefault mode: $this, " +
                            "supported modes: ${JvmDefaultMode.values().map { it.description }}"
                )
            }
            put(JvmAnalysisFlags.inheritMultifileParts, extractFlagArgumentValue(K2JVMCompilerArguments::inheritMultifileParts))
            put(JvmAnalysisFlags.sanitizeParentheses, extractFlagArgumentValue(K2JVMCompilerArguments::sanitizeParentheses))
            put(
                JvmAnalysisFlags.suppressMissingBuiltinsError,
                extractFlagArgumentValue(K2JVMCompilerArguments::suppressMissingBuiltinsError)
            )
            put(JvmAnalysisFlags.irCheckLocalNames, extractFlagArgumentValue(K2JVMCompilerArguments::irCheckLocalNames))
            put(
                AnalysisFlags.reportErrorsOnIrDependencies,
                !extractFlagArgumentValue(K2JVMCompilerArguments::useIR)
                        && !extractFlagArgumentValue(K2JVMCompilerArguments::useFir)
                        && !extractFlagArgumentValue(K2JVMCompilerArguments::allowJvmIrDependencies)
            )
            put(JvmAnalysisFlags.disableUltraLightClasses, extractFlagArgumentValue(K2JVMCompilerArguments::disableUltraLightClasses))
        }
    }

fun FlatCompilerArgumentsBucket.configureLanguageFeatures(
    targetPlatform: TargetPlatform,
    collector: MessageCollector
): MutableMap<LanguageFeature, LanguageFeature.State> =
    hashMapOf<LanguageFeature, LanguageFeature.State>().apply {
        if (extractFlagArgumentValue(CommonCompilerArguments::multiPlatform)) {
            put(LanguageFeature.MultiPlatformProjects, LanguageFeature.State.ENABLED)
        }

        when (val state = extractSingleArgumentValue(CommonCompilerArguments::coroutinesState)) {
            CommonCompilerArguments.ERROR -> put(LanguageFeature.Coroutines, LanguageFeature.State.ENABLED_WITH_ERROR)
            CommonCompilerArguments.ENABLE -> put(LanguageFeature.Coroutines, LanguageFeature.State.ENABLED)
            CommonCompilerArguments.WARN, CommonCompilerArguments.DEFAULT -> {
            }
            else -> {
                val message = "Invalid value of -Xcoroutines (should be: enable, warn or error): " + state
                collector.report(CompilerMessageSeverity.ERROR, message, null)
            }
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::newInference)) {
            put(LanguageFeature.NewInference, LanguageFeature.State.ENABLED)
            put(LanguageFeature.SamConversionPerArgument, LanguageFeature.State.ENABLED)
            put(LanguageFeature.FunctionReferenceWithDefaultValueAsOtherType, LanguageFeature.State.ENABLED)
            put(LanguageFeature.DisableCompatibilityModeForNewInference, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::inlineClasses)) {
            put(LanguageFeature.InlineClasses, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::polymorphicSignature)) {
            put(LanguageFeature.PolymorphicSignature, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::legacySmartCastAfterTry)) {
            put(LanguageFeature.SoundSmartCastsAfterTry, LanguageFeature.State.DISABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::effectSystem)) {
            put(LanguageFeature.UseCallsInPlaceEffect, LanguageFeature.State.ENABLED)
            put(LanguageFeature.UseReturnsEffect, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::readDeserializedContracts)) {
            put(LanguageFeature.ReadDeserializedContracts, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::properIeee754Comparisons)) {
            put(LanguageFeature.ProperIeee754Comparisons, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::useMixedNamedArguments)) {
            put(LanguageFeature.MixedNamedArgumentsInTheirOwnPosition, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::inferenceCompatibility)) {
            put(LanguageFeature.InferenceCompatibility, LanguageFeature.State.ENABLED)
        }

        if (extractFlagArgumentValue(CommonCompilerArguments::progressiveMode)) {
            LanguageFeature.values().filter { it.kind.enabledInProgressiveMode }.forEach {
                // Don't overwrite other settings: users may want to turn off some particular
                // breaking change manually instead of turning off whole progressive mode
                putIfAbsent(it, LanguageFeature.State.ENABLED)
            }
        }

        if (targetPlatform.isJvm() && extractFlagArgumentValue(K2JVMCompilerArguments::strictJavaNullabilityAssertions)) {
            put(LanguageFeature.StrictJavaNullabilityAssertions, LanguageFeature.State.ENABLED)
        }

        if (internalArguments.isNotEmpty()) {
            configureLanguageFeaturesFromInternalArgs(this@configureLanguageFeatures, collector)
        }
    }

private fun HashMap<LanguageFeature, LanguageFeature.State>.configureLanguageFeaturesFromInternalArgs(
    bucket: FlatCompilerArgumentsBucket,
    collector: MessageCollector
) {
    val errors = ArgumentParseErrors()

    val internalArgs = bucket.internalArguments.mapNotNull { arg ->
        InternalArgumentParser.PARSERS.firstOrNull { it.canParse(arg) }?.parseInternalArgument(arg, errors)
    }

    val (featuresThatForcePreReleaseBinaries,
        disabledFeaturesFromUnsupportedVersions,
        standaloneSamConversionFeaturePassedExplicitly,
        functionReferenceWithDefaultValueFeaturePassedExplicitly
    ) = configureFromInternalArgs(internalArgs)

    if (this[LanguageFeature.NewInference] == LanguageFeature.State.ENABLED) {
        if (!standaloneSamConversionFeaturePassedExplicitly)
            put(LanguageFeature.SamConversionPerArgument, LanguageFeature.State.ENABLED)

        if (!functionReferenceWithDefaultValueFeaturePassedExplicitly)
            put(LanguageFeature.FunctionReferenceWithDefaultValueAsOtherType, LanguageFeature.State.ENABLED)

        put(LanguageFeature.DisableCompatibilityModeForNewInference, LanguageFeature.State.ENABLED)
    }

    if (featuresThatForcePreReleaseBinaries.isNotEmpty()) {
        collector.report(
            CompilerMessageSeverity.STRONG_WARNING,
            "Following manually enabled features will force generation of pre-release binaries: ${featuresThatForcePreReleaseBinaries.joinToString()}"
        )
    }

    if (disabledFeaturesFromUnsupportedVersions.isNotEmpty()) {
        collector.report(
            CompilerMessageSeverity.ERROR,
            "The following features cannot be disabled manually, because the version they first appeared in is no longer " +
                    "supported:\n${disabledFeaturesFromUnsupportedVersions.joinToString()}"
        )
    }
}
