/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.facet

import com.intellij.facet.FacetManager
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ExternalProjectSystemRegistry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModel
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.caching.FlatArgsInfoImpl
import org.jetbrains.kotlin.caching.RawToFlatCompilerArgumentsBucketConverter
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.configuration.externalCompilerVersion
import org.jetbrains.kotlin.idea.core.isAndroidModule
import org.jetbrains.kotlin.idea.framework.KotlinSdkType
import org.jetbrains.kotlin.idea.platform.tooling
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.idea.util.getProjectJdkTableSafe
import org.jetbrains.kotlin.platform.*
import org.jetbrains.kotlin.platform.compat.toNewPlatform
import org.jetbrains.kotlin.platform.impl.JvmIdePlatformKind
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.NotNullableUserDataProperty
import kotlin.reflect.KProperty1

var Module.hasExternalSdkConfiguration: Boolean
        by NotNullableUserDataProperty(Key.create<Boolean>("HAS_EXTERNAL_SDK_CONFIGURATION"), false)

private fun getDefaultTargetPlatform(module: Module, rootModel: ModuleRootModel?): TargetPlatform {
    val platformKind = IdePlatformKind.ALL_KINDS.firstOrNull {
        getRuntimeLibraryVersions(module, rootModel, it).isNotEmpty()
    } ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform.idePlatformKind
    if (platformKind == JvmIdePlatformKind) {
        var jvmTarget = Kotlin2JvmCompilerArgumentsHolder.getInstance(module.project).settings.jvmTarget?.let { JvmTarget.fromString(it) }
        if (jvmTarget == null) {
            val sdk = ((rootModel ?: ModuleRootManager.getInstance(module))).sdk
            val sdkVersion = (sdk?.sdkType as? JavaSdk)?.getVersion(sdk)
            if (sdkVersion == null || sdkVersion >= JavaSdkVersion.JDK_1_8) {
                jvmTarget = JvmTarget.JVM_1_8
            }
        }
        return if (jvmTarget != null) JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget) else JvmPlatforms.defaultJvmPlatform
    }
    return platformKind.defaultPlatform
}

private fun KotlinFacetSettings.initializeCompilerArguments(
    module: Module,
    rootModel: ModuleRootModel?,
    commonArguments: CommonCompilerArguments,
    platform: TargetPlatform? = null, // if null, detect by module dependencies
) {
    if (compilerArgumentsBucket == null) {
        val targetPlatform = platform ?: getDefaultTargetPlatform(module, rootModel)
        compilerArgumentsBucket = createAndPrepareCompilerArguments(targetPlatform, module, commonArguments).toFlatCompilerArguments()
        this.targetPlatform = targetPlatform
    }

}

private fun KotlinFacetSettings.initializeCompilerArgumentsBucket(
    module: Module,
    rootModel: ModuleRootModel?,
    commonArguments: CommonCompilerArguments,
    platform: TargetPlatform? = null, // if null, detect by module dependencies
) {
    if (compilerArgumentsBucket == null) {
        val targetPlatform = platform ?: getDefaultTargetPlatform(module, rootModel)
        compilerArgumentsBucket = createAndPrepareCompilerArguments(targetPlatform, module, commonArguments).let {
            val arguments = ArgumentUtils.convertArgumentsToStringList(it)
            RawToFlatCompilerArgumentsBucketConverter(commonArguments::class.java.classLoader).convert(arguments)
        }
        this.targetPlatform = targetPlatform
    }
}

private fun createAndPrepareCompilerArguments(
    targetPlatform: TargetPlatform,
    module: Module,
    commonArguments: CommonCompilerArguments
) = targetPlatform.createArguments {
    targetPlatform.idePlatformKind.tooling.compilerArgumentsForProject(module.project)?.let { mergeBeans(it, this) }
    mergeBeans(commonArguments, this)
}

fun KotlinFacetSettings.initializeIfNeeded(
    module: Module,
    rootModel: ModuleRootModel?,
    platform: TargetPlatform? = null, // if null, detect by module dependencies
    compilerVersion: String? = null
) {
    val project = module.project

    val shouldInferLanguageLevel = languageLevel == null
    val shouldInferAPILevel = apiLevel == null

    if (compilerSettings == null) {
        compilerSettings = KotlinCompilerSettings.getInstance(project).settings
    }

    val commonArguments = KotlinCommonCompilerArgumentsHolder.getInstance(project).settings

    initializeCompilerArgumentsBucket(module, rootModel, commonArguments, platform)

    if (shouldInferLanguageLevel) {
        languageLevel = (if (useProjectSettings) LanguageVersion.fromVersionString(commonArguments.languageVersion) else null)
            ?: getDefaultLanguageLevel(module, compilerVersion, coerceRuntimeLibraryVersionToReleased = compilerVersion == null)
    }

    if (shouldInferAPILevel) {
        apiLevel = if (useProjectSettings) {
            LanguageVersion.fromVersionString(commonArguments.apiVersion) ?: languageLevel
        } else {
            languageLevel?.coerceAtMost(
                getLibraryLanguageLevel(
                    module,
                    rootModel,
                    this.targetPlatform?.idePlatformKind,
                    coerceRuntimeLibraryVersionToReleased = compilerVersion == null
                )
            )
        }
    }
}

val mavenLibraryIdToPlatform: Map<String, IdePlatformKind<*>> by lazy {
    IdePlatformKind.ALL_KINDS
        .flatMap { platform -> platform.tooling.mavenLibraryIds.map { it to platform } }
        .sortedByDescending { it.first.length }
        .toMap()
}

fun Module.getOrCreateFacet(
    modelsProvider: IdeModifiableModelsProvider,
    useProjectSettings: Boolean,
    externalSystemId: String? = null,
    commitModel: Boolean = false
): KotlinFacet {
    val facetModel = modelsProvider.getModifiableFacetModel(this)

    val facet = facetModel.findFacet(KotlinFacetType.TYPE_ID, KotlinFacetType.INSTANCE.defaultFacetName)
        ?: with(KotlinFacetType.INSTANCE) { createFacet(this@getOrCreateFacet, defaultFacetName, createDefaultConfiguration(), null) }
            .apply {
                val externalSource = externalSystemId?.let { ExternalProjectSystemRegistry.getInstance().getSourceById(it) }
                facetModel.addFacet(this, externalSource)
            }
    facet.configuration.settings.useProjectSettings = useProjectSettings
    if (commitModel) {
        runWriteAction {
            facetModel.commit()
        }
    }
    return facet
}

fun Module.hasKotlinFacet() = FacetManager.getInstance(this).getFacetByType(KotlinFacetType.TYPE_ID) != null

fun Module.removeKotlinFacet(
    modelsProvider: IdeModifiableModelsProvider,
    commitModel: Boolean = false
) {
    val facetModel = modelsProvider.getModifiableFacetModel(this)
    val facet = facetModel.findFacet(KotlinFacetType.TYPE_ID, KotlinFacetType.INSTANCE.defaultFacetName) ?: return
    facetModel.removeFacet(facet)
    if (commitModel) {
        runWriteAction {
            facetModel.commit()
        }
    }
}

//method used for non-mpp modules
fun KotlinFacet.configureFacet(
    compilerVersion: String?,
    coroutineSupport: LanguageFeature.State,
    platform: TargetPlatform?,
    modelsProvider: IdeModifiableModelsProvider
) {
    configureFacet(compilerVersion, coroutineSupport, platform, modelsProvider, false, emptyList(), emptyList())
}

fun KotlinFacet.configureFacet(
    compilerVersion: String?,
    coroutineSupport: LanguageFeature.State,
    platform: TargetPlatform?, // if null, detect by module dependencies
    modelsProvider: IdeModifiableModelsProvider,
    hmppEnabled: Boolean,
    pureKotlinSourceFolders: List<String>,
    dependsOnList: List<String>
) {
    val module = module
    with(configuration.settings) {
        targetPlatform = null
        compilerSettings = null
        isHmppEnabled = hmppEnabled
        dependsOnModuleNames = dependsOnList
        initializeIfNeeded(
            module,
            modelsProvider.getModifiableRootModel(module),
            platform,
            compilerVersion
        )
        val apiLevel = apiLevel
        val languageLevel = languageLevel
        if (languageLevel != null && apiLevel != null && apiLevel > languageLevel) {
            this.apiLevel = languageLevel
        }
        this.coroutineSupport = if (languageLevel != null && languageLevel < LanguageVersion.KOTLIN_1_3) coroutineSupport else null
        this.pureKotlinSourceFolders = pureKotlinSourceFolders
    }

    module.externalCompilerVersion = compilerVersion
}

private fun Module.externalSystemRunTasks(): List<ExternalSystemRunTask> {
    val settingsProvider = KotlinFacetSettingsProvider.getInstance(project) ?: return emptyList()
    return settingsProvider.getInitializedSettings(this).externalSystemRunTasks
}

fun Module.externalSystemTestRunTasks() = externalSystemRunTasks().filterIsInstance<ExternalSystemTestRunTask>()
fun Module.externalSystemNativeMainRunTasks() = externalSystemRunTasks().filterIsInstance<ExternalSystemNativeMainRunTask>()

@Suppress("DEPRECATION_ERROR", "DeprecatedCallableAddReplaceWith")
@Deprecated(
    message = "IdePlatform is deprecated and will be removed soon, please, migrate to org.jetbrains.kotlin.platform.TargetPlatform",
    level = DeprecationLevel.ERROR
)
fun KotlinFacet.configureFacet(
    compilerVersion: String?,
    coroutineSupport: LanguageFeature.State,
    platform: IdePlatform<*, *>,
    modelsProvider: IdeModifiableModelsProvider
) {
    configureFacet(compilerVersion, coroutineSupport, platform.toNewPlatform(), modelsProvider)
}

fun KotlinFacet.noVersionAutoAdvance() {
    configuration.settings.apply {
        autoAdvanceLanguageVersion = false
        autoAdvanceApiVersion = false
    }
}

// "Primary" fields are written to argument beans directly and thus not presented in the "additional arguments" string
// Update these lists when facet/project settings UI changes
val commonUIExposedFields = listOf(
    CommonCompilerArguments::languageVersion.name,
    CommonCompilerArguments::apiVersion.name,
    CommonCompilerArguments::suppressWarnings.name,
    CommonCompilerArguments::coroutinesState.name
)
private val commonUIHiddenFields = listOf(
    CommonCompilerArguments::pluginClasspaths.name,
    CommonCompilerArguments::pluginOptions.name,
    CommonCompilerArguments::multiPlatform.name
)
val commonPrimaryFields = commonUIExposedFields + commonUIHiddenFields

private val jvmSpecificUIExposedFields = listOf(
    K2JVMCompilerArguments::jvmTarget.name,
    K2JVMCompilerArguments::destination.name,
    K2JVMCompilerArguments::classpath.name
)
private val jvmSpecificUIHiddenFields = listOf(
    K2JVMCompilerArguments::friendPaths.name
)
val jvmUIExposedFields = commonUIExposedFields + jvmSpecificUIExposedFields
val jvmPrimaryFields = commonPrimaryFields + jvmSpecificUIExposedFields + jvmSpecificUIHiddenFields

private val jsSpecificUIExposedFields = listOf(
    K2JSCompilerArguments::sourceMap.name,
    K2JSCompilerArguments::sourceMapPrefix.name,
    K2JSCompilerArguments::sourceMapEmbedSources.name,
    K2JSCompilerArguments::outputPrefix.name,
    K2JSCompilerArguments::outputPostfix.name,
    K2JSCompilerArguments::moduleKind.name
)
val jsUIExposedFields = commonUIExposedFields + jsSpecificUIExposedFields
val jsPrimaryFields = commonPrimaryFields + jsSpecificUIExposedFields

private val metadataSpecificUIExposedFields = listOf(
    K2MetadataCompilerArguments::destination.name,
    K2MetadataCompilerArguments::classpath.name
)
val metadataUIExposedFields = commonUIExposedFields + metadataSpecificUIExposedFields
val metadataPrimaryFields = commonPrimaryFields + metadataSpecificUIExposedFields

fun parseCompilerArgumentsToFacet(
    arguments: List<String>,
    defaultArguments: List<String>,
    kotlinFacet: KotlinFacet,
    modelsProvider: IdeModifiableModelsProvider?
) {
    val targetPlatform = kotlinFacet.configuration.settings.targetPlatform ?: return
    with(RawToFlatCompilerArgumentsBucketConverter(CommonCompilerArguments::class.java.classLoader)) {
        val argumentsBucket = convert(arguments, targetPlatform.serializeComponentPlatforms())
        val defaultBucket = convert(defaultArguments, targetPlatform.serializeComponentPlatforms())
        configureFacetByFlatArgsInfo(kotlinFacet, FlatArgsInfoImpl(argumentsBucket, defaultBucket), modelsProvider)
    }
}

internal fun joinPluginOptions(old: Array<String>?, new: Array<String>?): Array<String>? {
    if (old == null && new == null) {
        return old
    } else if (new == null) {
        return old
    } else if (old == null) {
        return new
    }

    return (old + new).distinct().toTypedArray()
}
