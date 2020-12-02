/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.classic

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.analyzer.common.CommonResolverForModuleFactory
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.js.analyze.TopDownAnalyzerFacadeForJS
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.js.isJs
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.serialization.deserialization.MetadataPartProvider
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.*

class ClassicFrontendFacade(
    testServices: TestServices
) : FrontendFacade<ClassicFrontendOutputArtifact>(testServices, FrontendKinds.ClassicFrontend) {
    override val additionalServices: List<ServiceRegistrationData>
        get() = listOf(service(::ModuleDescriptorProvider))

    override fun analyze(module: TestModule): ClassicFrontendOutputArtifact {
        val dependencyProvider = testServices.dependencyProvider
        val moduleDescriptorProvider = testServices.moduleDescriptorProvider
        val environment = testServices.kotlinCoreEnvironmentProvider.getKotlinCoreEnvironment(module)
        val project = environment.project

        val ktFilesMap = testServices.sourceFileProvider.getKtFilesForSourceFiles(module.files, project)
        val ktFiles = ktFilesMap.values.toMutableList()
        val languageVersionSettings = module.languageVersionSettings

        val sourceDependencies = module.dependencies.filter { it.kind == DependencyKind.Source }
        val dependentDescriptors = sourceDependencies.map {
            val testModule = dependencyProvider.getTestModule(it.moduleName)
            moduleDescriptorProvider.getModuleDescriptor(testModule)
        }

        sourceDependencies.flatMapTo(ktFiles) {
            val dependencyModule = dependencyProvider.getTestModule(it.moduleName)
            if (dependencyModule.targetPlatform.isCommon()) {
                dependencyProvider.getArtifact(dependencyModule, FrontendKinds.ClassicFrontend).ktFiles.values
            } else {
                emptyList()
            }
        }

        val analysisResult = performResolve(module, project, environment, ktFiles, languageVersionSettings, dependentDescriptors)
        moduleDescriptorProvider.replaceModuleDescriptorForModule(module, analysisResult.moduleDescriptor)
        return ClassicFrontendOutputArtifact(
            ktFilesMap,
            analysisResult,
            project,
            languageVersionSettings
        )
    }

    private fun performResolve(
        module: TestModule,
        project: Project,
        environment: KotlinCoreEnvironment,
        files: List<KtFile>,
        languageVersionSettings: LanguageVersionSettings,
        dependentDescriptors: List<ModuleDescriptorImpl>
    ): AnalysisResult {
        val targetPlatform = module.targetPlatform
        return when {
            targetPlatform.isJvm() -> performJvmModuleResolve(project, environment, files, dependentDescriptors)
            targetPlatform.isJs() -> performJsModuleResolve(project, environment, files, dependentDescriptors)
            targetPlatform.isNative() -> TODO()
            targetPlatform.isCommon() -> performCommonModuleResolve(module, files, languageVersionSettings)
            else -> error("Should not be here")
        }
    }

    private fun performJvmModuleResolve(
        project: Project,
        environment: KotlinCoreEnvironment,
        files: List<KtFile>,
        dependentDescriptors: List<ModuleDescriptorImpl>
    ): AnalysisResult {
        return TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            project,
            files,
            NoScopeRecordCliBindingTrace(),
            environment.configuration.copy(),
            environment::createPackagePartProvider,
            explicitModuleDependencyList = dependentDescriptors
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun performJsModuleResolve(
        project: Project,
        environment: KotlinCoreEnvironment,
        files: List<KtFile>,
        dependentDescriptors: List<ModuleDescriptorImpl>
    ): AnalysisResult {
        val configuration = environment.configuration
        val jsConfig = JsConfig(project, configuration)
        val dependentDescriptorsIncludingLibraries = buildList {
            addAll(dependentDescriptors)
            addAll(jsConfig.moduleDescriptors)
        }
        return TopDownAnalyzerFacadeForJS.analyzeFiles(
            files,
            project,
            configuration,
            moduleDescriptors = dependentDescriptorsIncludingLibraries,
            friendModuleDescriptors = emptyList()
        )
    }

    private fun performCommonModuleResolve(
        module: TestModule,
        files: List<KtFile>,
        languageVersionSettings: LanguageVersionSettings,
    ): AnalysisResult {
        return CommonResolverForModuleFactory.analyzeFiles(
            files,
            Name.special("<${module.name}>"),
            dependOnBuiltIns = true,
            languageVersionSettings,
            module.targetPlatform,
            // TODO: add dependency manager
        ) { _ ->
            // TODO
            MetadataPartProvider.Empty
        }
    }
}
