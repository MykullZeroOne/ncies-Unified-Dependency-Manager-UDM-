package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import java.io.File

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
                    file.name == GradleConstants.KOTLIN_DSL_SCRIPT_NAME) {
                    buildFiles.add(file)
                }
            }
        }
        collect(projectRoot)
        return buildFiles
    }

    private fun parseGroovyDependencies(file: GroovyFile, moduleName: String, filePath: String): List<InstalledDependency> {
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
                            result.add(InstalledDependency(
                                groupId = parts[0],
                                artifactId = parts[1],
                                version = parts[2],
                                configuration = methodName,
                                moduleName = moduleName,
                                buildFile = filePath,
                                offset = call.textRange.startOffset,
                                length = call.textRange.endOffset - call.textRange.startOffset
                            ))
                        }
                    }
                }
            }
        }
        return result
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
                            result.add(InstalledDependency(
                                groupId = parts[0],
                                artifactId = parts[1],
                                version = parts[2],
                                configuration = methodName,
                                moduleName = moduleName,
                                buildFile = filePath,
                                offset = call.textRange.startOffset,
                                length = call.textRange.endOffset - call.textRange.startOffset
                            ))
                        }
                    }
                }
            }
        }
        return result
    }

    private fun isDependencyConfiguration(name: String): Boolean {
        return name in listOf("implementation", "api", "testImplementation", "runtimeOnly", "compileOnly", "annotationProcessor", "testRuntimeOnly", "testCompileOnly")
    }
}
