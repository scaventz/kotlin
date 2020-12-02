/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.classic.handlers

import org.jetbrains.kotlin.checkers.diagnostics.SyntaxErrorDiagnostic
import org.jetbrains.kotlin.checkers.utils.CheckerTestUtil
import org.jetbrains.kotlin.checkers.utils.DiagnosticsRenderingConfiguration
import org.jetbrains.kotlin.codeMetaInfo.model.CodeMetaInfo
import org.jetbrains.kotlin.codeMetaInfo.model.DiagnosticCodeMetaInfo
import org.jetbrains.kotlin.codeMetaInfo.model.ParsedCodeMetaInfo
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.AnalyzingUtils
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactoryImpl
import org.jetbrains.kotlin.test.directives.DiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontendOutputArtifact
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.*

class ClassicDiagnosticsHandler(testServices: TestServices) : ClassicFrontendAnalysisHandler(testServices) {
    override val directivesContainers: List<DirectivesContainer> =
        listOf(DiagnosticsDirectives)

    override val additionalServices: List<ServiceRegistrationData> =
        listOf(service(::DiagnosticsService))

    private val globalMetadataInfoHandler: GlobalMetadataInfoHandler
        get() = testServices.globalMetadataInfoHandler

    private val diagnosticsService: DiagnosticsService
        get() = testServices.diagnosticsService

    @OptIn(ExperimentalStdlibApi::class)
    override fun processModule(module: TestModule, info: ClassicFrontendOutputArtifact) {
        val diagnosticsPerFile = info.analysisResult.bindingContext.diagnostics.all().groupBy {
            it.psiFile
        }

        val withNewInferenceModeEnabled = testServices.withNewInferenceModeEnabled(module)

        val configuration = DiagnosticsRenderingConfiguration(
            platform = null,
            withNewInference = info.languageVersionSettings.supportsFeature(LanguageFeature.NewInference),
            languageVersionSettings = info.languageVersionSettings,
            skipDebugInfoDiagnostics = testServices.kotlinCoreEnvironmentProvider.getCompilerConfiguration(module)
                .getBoolean(JVMConfigurationKeys.IR)
        )

        for (file in module.files) {
            val ktFile = info.ktFiles[file] ?: continue
            val diagnostics = diagnosticsPerFile[ktFile] ?: continue
            for (diagnostic in diagnostics) {
                if (!diagnosticsService.shouldRenderDiagnostic(module, diagnostic.factory.name)) continue
                globalMetadataInfoHandler.addMetadataInfosForFile(
                    file,
                    diagnostic.toMetaInfo(
                        file,
                        configuration.withNewInference,
                        withNewInferenceModeEnabled
                    )
                )
            }
            for (errorElement in AnalyzingUtils.getSyntaxErrorRanges(ktFile)) {
                globalMetadataInfoHandler.addMetadataInfosForFile(
                    file,
                    SyntaxErrorDiagnostic(errorElement).toMetaInfo(file, configuration.withNewInference, withNewInferenceModeEnabled)
                )
            }
            processDebugInfoDiagnostics(configuration, file, ktFile, info, withNewInferenceModeEnabled)
        }
    }

    private fun Diagnostic.toMetaInfo(
        file: TestFile,
        newInferenceEnabled: Boolean,
        withNewInferenceModeEnabled: Boolean
    ): List<DiagnosticCodeMetaInfo> = textRanges.map { range ->
        val metaInfo = DiagnosticCodeMetaInfo(range, ClassicMetaInfoUtils.renderDiagnosticNoArgs, this)
        if (withNewInferenceModeEnabled) {
            metaInfo.attributes += if (newInferenceEnabled) OldNewInferenceMetaInfoProcessor.NI else OldNewInferenceMetaInfoProcessor.OI
        }
        val existing = globalMetadataInfoHandler.getExistingMetaInfosForActualMetadata(file, metaInfo)
        if (existing.any { it.description != null }) {
            metaInfo.replaceRenderConfiguration(ClassicMetaInfoUtils.renderDiagnosticWithArgs)
        }
        metaInfo
    }

    private fun processDebugInfoDiagnostics(
        configuration: DiagnosticsRenderingConfiguration,
        file: TestFile,
        ktFile: KtFile,
        info: ClassicFrontendOutputArtifact,
        withNewInferenceModeEnabled: Boolean
    ) {
        val debugAnnotations = CheckerTestUtil.getDebugInfoDiagnostics(
            ktFile,
            info.analysisResult.bindingContext,
            markDynamicCalls = true,
            dynamicCallDescriptors = mutableListOf(),
            configuration,
            dataFlowValueFactory = DataFlowValueFactoryImpl(info.languageVersionSettings),
            info.analysisResult.moduleDescriptor as ModuleDescriptorImpl,
            diagnosedRanges = null
        )
        debugAnnotations.map { debugAnnotation ->
            globalMetadataInfoHandler.addMetadataInfosForFile(
                file,
                debugAnnotation.diagnostic.toMetaInfo(file, configuration.withNewInference, withNewInferenceModeEnabled)
            )
        }
    }

    override fun processAfterAllModules() {}
}

class OldNewInferenceMetaInfoProcessor(testServices: TestServices) : AdditionalMetaInfoProcessor(testServices) {
    companion object {
        const val OI = "OI"
        const val NI = "NI"
    }

    /*
     * Rules for OI/NI attribute:
     * ┌──────────┬──────┬──────┬──────────┐
     * │          │  OI  │  NI  │ nothing  │ <- reported
     * ├──────────┼──────┼──────┼──────────┤
     * │  nothing │ both │ both │ nothing  │
     * │    OI    │  OI  │ both │   OI     │
     * │    NI    │ both │  NI  │   NI     │
     * │   both   │ both │ both │ opposite │ <- OI if NI enabled in test and vice versa
     * └──────────┴──────┴──────┴──────────┘
     *       ^ existed
     */
    override fun processMetaInfos(module: TestModule, file: TestFile) {
        if (!testServices.withNewInferenceModeEnabled(module)) return
        val newInferenceEnabled = module.languageVersionSettings.supportsFeature(LanguageFeature.NewInference)
        val (currentFlag, otherFlag) = when (newInferenceEnabled) {
            true -> NI to OI
            false -> OI to NI
        }
        val matchedExistedInfos = mutableSetOf<ParsedCodeMetaInfo>()
        val matchedReportedInfos = mutableSetOf<CodeMetaInfo>()
        val allReportedInfos = globalMetadataInfoHandler.getReportedMetaInfosForFile(file)
        for ((_, reportedInfos) in allReportedInfos.groupBy { it.start to it.end }) {
            val existedInfos = globalMetadataInfoHandler.getExistingMetaInfosForActualMetadata(file, reportedInfos.first())
            for ((reportedInfo, existedInfo) in reportedInfos.zip(existedInfos)) {
                matchedExistedInfos += existedInfo
                matchedReportedInfos += reportedInfo
                if (currentFlag !in reportedInfo.attributes) continue
                if (currentFlag in existedInfo.attributes) continue
                reportedInfo.attributes.remove(currentFlag)
            }
        }

        if (allReportedInfos.size != matchedReportedInfos.size) {
            for (info in allReportedInfos) {
                if (info !in matchedReportedInfos) {
                    info.attributes.remove(currentFlag)
                }
            }
        }

        val allExistedInfos = globalMetadataInfoHandler.getExistingMetaInfosForFile(file)
        if (allExistedInfos.size == matchedExistedInfos.size) return

        val newInfos = allExistedInfos.mapNotNull {
            if (it in matchedExistedInfos) return@mapNotNull null
            if (currentFlag in it.attributes) return@mapNotNull null
            it.copy().apply {
                if (otherFlag !in attributes) {
                    attributes += otherFlag
                }
            }
        }
        globalMetadataInfoHandler.addMetadataInfosForFile(file, newInfos)
    }
}

private fun TestServices.withNewInferenceModeEnabled(module: TestModule): Boolean {
    return moduleStructure.globalDirectives.contains(DiagnosticsDirectives.WITH_NEW_INFERENCE) ||
            module.directives.contains(DiagnosticsDirectives.WITH_NEW_INFERENCE)
}
