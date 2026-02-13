package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import java.io.File

/**
 * A utility class for scanning Gradle build files within a given project to identify and process
 * declared dependencies. The class supports both Groovy and Kotlin DSL Gradle files and provides
 * methods for retrieving information about installed dependencies and module build files.
 *
 * @param project The IntelliJ IDEA `Project` instance containing the Gradle build files to scan.
 */
class GradleDependencyScanner(private val project: Project) {

    fun scanInstalledDependencies(): List<InstalledDependency> {
        return ReadAction.compute<List<InstalledDependency>, Exception> {
            val dependencies = mutableListOf<InstalledDependency>()
            val gradleFiles = findGradleBuildFiles()

            for (file in gradleFiles) {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
                val moduleName = file.parent?.name ?: "root"

                when (psiFile) {
                    is GroovyFile -> {
                        dependencies.addAll(parseGroovyDependencies(psiFile, moduleName, file.path))
                    }

                    is KtFile -> {
                        dependencies.addAll(parseKotlinDependencies(psiFile, moduleName, file.path))
                    }
                }
            }
            dependencies
        }
    }

    fun getModuleBuildFiles(): Map<String, VirtualFile> {
        return ReadAction.compute<Map<String, VirtualFile>, Exception> {
            val result = mutableMapOf<String, VirtualFile>()
            findGradleBuildFiles().forEach { file ->
                val moduleName = file.parent?.name ?: "root"
                result[moduleName] = file
            }
            result
        }
    }

    private fun findGradleBuildFiles(): List<VirtualFile> {
        val buildFiles = mutableListOf<VirtualFile>()
        val basePath = project.basePath ?: return emptyList()
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()

        fun collect(file: VirtualFile) {
            if (file.isDirectory) {
                // Skip common non-project directories to be faster
                if (file.name == "build" || file.name == ".gradle" || file.name == ".idea" || file.name == "node_modules") return
                file.children.forEach { collect(it) }
            } else {
                if (file.name == GradleConstants.DEFAULT_SCRIPT_NAME ||
                    file.name == GradleConstants.KOTLIN_DSL_SCRIPT_NAME
                ) {
                    buildFiles.add(file)
                }
            }
        }
        collect(projectRoot)
        return buildFiles
    }

    private fun parseGroovyDependencies(
        file: GroovyFile,
        moduleName: String,
        filePath: String
    ): List<InstalledDependency> {
        val result = mutableListOf<InstalledDependency>()
        PsiTreeUtil.findChildrenOfType(file, GrMethodCall::class.java).forEach { call ->
            val methodName = call.invokedExpression.text
            if (isDependencyConfiguration(methodName)) {
                val args = call.argumentList?.expressionArguments
                if (args != null && args.isNotEmpty()) {
                    val firstArg = args.firstOrNull()
                    if (firstArg != null) {
                        val depString = firstArg.text.removeSurrounding("'").removeSurrounding("\"")
                        val parts = depString.split(":")
                        if (parts.size >= 3) {
                            // Parse exclusions from closure if present
                            val exclusions = parseGroovyExclusions(call)
                            result.add(
                                InstalledDependency(
                                    groupId = parts[0],
                                    artifactId = parts[1],
                                    version = parts[2],
                                    configuration = methodName,
                                    moduleName = moduleName,
                                    buildFile = filePath,
                                    offset = call.textRange.startOffset,
                                    length = call.textRange.endOffset - call.textRange.startOffset,
                                    exclusions = exclusions
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Parse exclusions from a Groovy DSL dependency closure.
     * Handles: implementation('g:a:v') { exclude group: 'g', module: 'm' }
     */
    private fun parseGroovyExclusions(call: GrMethodCall): List<DependencyExclusion> {
        val exclusions = mutableListOf<DependencyExclusion>()
        val closures = PsiTreeUtil.findChildrenOfType(call, GrClosableBlock::class.java)
        for (closure in closures) {
            val excludeCalls = PsiTreeUtil.findChildrenOfType(closure, GrMethodCall::class.java)
            for (excludeCall in excludeCalls) {
                if (excludeCall.invokedExpression.text == "exclude") {
                    var group: String? = null
                    var module: String? = null
                    val namedArgs = PsiTreeUtil.findChildrenOfType(excludeCall, GrNamedArgument::class.java)
                    for (arg in namedArgs) {
                        when (arg.labelName) {
                            "group" -> group = arg.expression?.text?.removeSurrounding("'")?.removeSurrounding("\"")
                            "module" -> module = arg.expression?.text?.removeSurrounding("'")?.removeSurrounding("\"")
                        }
                    }
                    if (group != null) {
                        exclusions.add(DependencyExclusion(groupId = group, artifactId = module))
                    }
                }
            }
        }
        return exclusions
    }

    private fun parseKotlinDependencies(file: KtFile, moduleName: String, filePath: String): List<InstalledDependency> {
        val result = mutableListOf<InstalledDependency>()
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).forEach { call ->
            val methodName = call.calleeExpression?.text ?: ""
            if (isDependencyConfiguration(methodName)) {
                val args = call.valueArguments
                if (args.isNotEmpty()) {
                    val firstArg = args[0].getArgumentExpression()
                    if (firstArg is KtStringTemplateExpression) {
                        val depString = firstArg.entries.joinToString("") { it.text }
                        val parts = depString.split(":")
                        if (parts.size >= 3) {
                            // Parse exclusions from trailing lambda if present
                            val exclusions = parseKotlinExclusions(call)
                            result.add(
                                InstalledDependency(
                                    groupId = parts[0],
                                    artifactId = parts[1],
                                    version = parts[2],
                                    configuration = methodName,
                                    moduleName = moduleName,
                                    buildFile = filePath,
                                    offset = call.textRange.startOffset,
                                    length = call.textRange.endOffset - call.textRange.startOffset,
                                    exclusions = exclusions
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Parse exclusions from a Kotlin DSL dependency trailing lambda.
     * Handles: implementation("g:a:v") { exclude(group = "g", module = "m") }
     */
    private fun parseKotlinExclusions(call: KtCallExpression): List<DependencyExclusion> {
        val exclusions = mutableListOf<DependencyExclusion>()
        val lambdas = call.lambdaArguments
        for (lambdaArg in lambdas) {
            val lambda = lambdaArg.getLambdaExpression() ?: continue
            val body = lambda.bodyExpression ?: continue
            val excludeCalls = PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java)
            for (excludeCall in excludeCalls) {
                if (excludeCall.calleeExpression?.text == "exclude") {
                    var group: String? = null
                    var module: String? = null
                    for (arg in excludeCall.valueArguments) {
                        val name = arg.getArgumentName()?.asName?.asString()
                        val value = (arg.getArgumentExpression() as? KtStringTemplateExpression)
                            ?.entries?.joinToString("") { it.text }
                        when (name) {
                            "group" -> group = value
                            "module" -> module = value
                        }
                    }
                    if (group != null) {
                        exclusions.add(DependencyExclusion(groupId = group, artifactId = module))
                    }
                }
            }
        }
        return exclusions
    }

    private fun isDependencyConfiguration(name: String): Boolean {
        return name in listOf(
            "implementation",
            "api",
            "testImplementation",
            "runtimeOnly",
            "compileOnly",
            "annotationProcessor",
            "testRuntimeOnly",
            "testCompileOnly"
        )
    }
}
