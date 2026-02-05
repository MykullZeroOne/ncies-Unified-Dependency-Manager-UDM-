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
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression

/**
 * Scans Gradle build files for installed plugins.
 * Supports both Kotlin DSL (.kts) and Groovy DSL (.gradle) files.
 */
class GradlePluginScanner(private val project: Project) {

    /**
     * Scan all Gradle build files in the project for installed plugins.
     */
    fun scanInstalledPlugins(): List<InstalledPlugin> {
        return ReadAction.compute<List<InstalledPlugin>, Exception> {
            val plugins = mutableListOf<InstalledPlugin>()
            val gradleFiles = findGradleBuildFiles()

            for (file in gradleFiles) {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
                val moduleName = getModuleName(file)

                when (psiFile) {
                    is GroovyFile -> {
                        plugins.addAll(parseGroovyPlugins(psiFile, moduleName, file.path))
                    }
                    is KtFile -> {
                        plugins.addAll(parseKotlinPlugins(psiFile, moduleName, file.path))
                    }
                }
            }
            plugins
        }
    }

    /**
     * Get a mapping of module names to their build files.
     */
    fun getModuleBuildFiles(): Map<String, VirtualFile> {
        return ReadAction.compute<Map<String, VirtualFile>, Exception> {
            val result = mutableMapOf<String, VirtualFile>()
            findGradleBuildFiles().forEach { file ->
                val moduleName = getModuleName(file)
                result[moduleName] = file
            }
            result
        }
    }

    private fun getModuleName(file: VirtualFile): String {
        val basePath = project.basePath
        if (basePath != null && file.parent?.path == basePath) {
            return "root"
        }
        return file.parent?.name ?: "root"
    }

    private fun findGradleBuildFiles(): List<VirtualFile> {
        val buildFiles = mutableListOf<VirtualFile>()
        val basePath = project.basePath ?: return emptyList()
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()

        fun collect(file: VirtualFile) {
            if (file.isDirectory) {
                // Skip common non-project directories
                if (file.name in listOf("build", ".gradle", ".idea", "node_modules", "out", "target")) return
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

    /**
     * Parse plugins from Kotlin DSL build files.
     * Handles various syntax forms:
     * - id("com.example.plugin") version "1.0.0"
     * - id("com.example.plugin")
     * - kotlin("jvm") version "1.9.0"
     * - `java-library`
     */
    private fun parseKotlinPlugins(file: KtFile, moduleName: String, filePath: String): List<InstalledPlugin> {
        val result = mutableListOf<InstalledPlugin>()
        val fileText = file.text

        // Find the plugins { } block
        val pluginsBlockStart = findPluginsBlockStart(fileText, isKotlin = true)
        if (pluginsBlockStart == -1) return result

        val pluginsBlockEnd = findBlockEnd(fileText, pluginsBlockStart)
        if (pluginsBlockEnd == -1) return result

        // Parse within the plugins block
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).forEach { call ->
            val callOffset = call.textRange.startOffset
            if (callOffset < pluginsBlockStart || callOffset > pluginsBlockEnd) return@forEach

            val calleeText = call.calleeExpression?.text ?: return@forEach

            when {
                // Handle id("plugin") or id("plugin") version "x.y.z"
                calleeText == "id" -> {
                    parseIdPlugin(call, moduleName, filePath, isKotlin = true)?.let { result.add(it) }
                }
                // Handle kotlin("jvm") or kotlin("jvm") version "x.y.z"
                calleeText == "kotlin" -> {
                    parseKotlinShorthandPlugin(call, moduleName, filePath)?.let { result.add(it) }
                }
            }
        }

        // Handle backtick plugins like `java-library`
        val backtickPattern = Regex("""`([a-zA-Z0-9\-]+)`""")
        backtickPattern.findAll(fileText.substring(pluginsBlockStart, pluginsBlockEnd + 1)).forEach { match ->
            val pluginId = match.groupValues[1]
            val offset = pluginsBlockStart + match.range.first
            result.add(InstalledPlugin(
                pluginId = pluginId,
                version = null,
                moduleName = moduleName,
                buildFile = filePath,
                offset = offset,
                length = match.value.length,
                pluginSyntax = PluginSyntax.BACKTICK,
                isKotlinShorthand = false,
                isApplied = true
            ))
        }

        return result
    }

    /**
     * Parse plugins from Groovy DSL build files.
     * Handles:
     * - id 'com.example.plugin' version '1.0.0'
     * - id 'com.example.plugin'
     * - java-library (shorthand)
     */
    private fun parseGroovyPlugins(file: GroovyFile, moduleName: String, filePath: String): List<InstalledPlugin> {
        val result = mutableListOf<InstalledPlugin>()
        val fileText = file.text

        // Find the plugins { } block
        val pluginsBlockStart = findPluginsBlockStart(fileText, isKotlin = false)
        if (pluginsBlockStart == -1) return result

        val pluginsBlockEnd = findBlockEnd(fileText, pluginsBlockStart)
        if (pluginsBlockEnd == -1) return result

        PsiTreeUtil.findChildrenOfType(file, GrMethodCall::class.java).forEach { call ->
            val callOffset = call.textRange.startOffset
            if (callOffset < pluginsBlockStart || callOffset > pluginsBlockEnd) return@forEach

            val methodName = call.invokedExpression.text

            when {
                // Handle id 'plugin' or id 'plugin' version 'x.y.z'
                methodName == "id" || methodName.startsWith("id ") -> {
                    parseGroovyIdPlugin(call, moduleName, filePath)?.let { result.add(it) }
                }
            }
        }

        // Handle shorthand plugins (just the plugin name without id)
        // These appear as reference expressions in Groovy
        PsiTreeUtil.findChildrenOfType(file, GrReferenceExpression::class.java).forEach { ref ->
            val refOffset = ref.textRange.startOffset
            if (refOffset < pluginsBlockStart || refOffset > pluginsBlockEnd) return@forEach

            val text = ref.text
            // Check if this is a standalone shorthand plugin (like 'java' or 'java-library')
            if (isBuiltInPlugin(text) && ref.parent !is GrMethodCall) {
                result.add(InstalledPlugin(
                    pluginId = text,
                    version = null,
                    moduleName = moduleName,
                    buildFile = filePath,
                    offset = refOffset,
                    length = ref.textRange.length,
                    pluginSyntax = PluginSyntax.GROOVY_SHORTHAND,
                    isKotlinShorthand = false,
                    isApplied = true
                ))
            }
        }

        return result
    }

    private fun parseIdPlugin(call: KtCallExpression, moduleName: String, filePath: String, isKotlin: Boolean): InstalledPlugin? {
        val args = call.valueArguments
        if (args.isEmpty()) return null

        val firstArg = args[0].getArgumentExpression()
        val pluginId = when (firstArg) {
            is KtStringTemplateExpression -> firstArg.entries.joinToString("") { it.text }
            else -> firstArg?.text?.removeSurrounding("\"")
        } ?: return null

        // Check for version - look for parent that is a dot qualified expression with version call
        var version: String? = null
        var fullLength = call.textRange.length
        var syntax = if (isKotlin) PluginSyntax.ID_ONLY else PluginSyntax.GROOVY_ID_ONLY

        val parent = call.parent
        if (parent is KtDotQualifiedExpression) {
            val selectorExpr = parent.selectorExpression
            if (selectorExpr is KtCallExpression && selectorExpr.calleeExpression?.text == "version") {
                val versionArgs = selectorExpr.valueArguments
                if (versionArgs.isNotEmpty()) {
                    val versionArg = versionArgs[0].getArgumentExpression()
                    version = when (versionArg) {
                        is KtStringTemplateExpression -> versionArg.entries.joinToString("") { it.text }
                        else -> versionArg?.text?.removeSurrounding("\"")
                    }
                    fullLength = parent.textRange.length
                    syntax = if (isKotlin) PluginSyntax.ID_VERSION else PluginSyntax.GROOVY_ID_VERSION
                }
            }
        }

        return InstalledPlugin(
            pluginId = pluginId,
            version = version,
            moduleName = moduleName,
            buildFile = filePath,
            offset = if (parent is KtDotQualifiedExpression && version != null) parent.textRange.startOffset else call.textRange.startOffset,
            length = fullLength,
            pluginSyntax = syntax,
            isKotlinShorthand = false,
            isApplied = true
        )
    }

    private fun parseKotlinShorthandPlugin(call: KtCallExpression, moduleName: String, filePath: String): InstalledPlugin? {
        val args = call.valueArguments
        if (args.isEmpty()) return null

        val firstArg = args[0].getArgumentExpression()
        val shortName = when (firstArg) {
            is KtStringTemplateExpression -> firstArg.entries.joinToString("") { it.text }
            else -> firstArg?.text?.removeSurrounding("\"")
        } ?: return null

        // Convert shorthand to full plugin ID
        val pluginId = "org.jetbrains.kotlin.$shortName"

        // Check for version
        var version: String? = null
        var fullLength = call.textRange.length
        var startOffset = call.textRange.startOffset

        val parent = call.parent
        if (parent is KtDotQualifiedExpression) {
            val selectorExpr = parent.selectorExpression
            if (selectorExpr is KtCallExpression && selectorExpr.calleeExpression?.text == "version") {
                val versionArgs = selectorExpr.valueArguments
                if (versionArgs.isNotEmpty()) {
                    val versionArg = versionArgs[0].getArgumentExpression()
                    version = when (versionArg) {
                        is KtStringTemplateExpression -> versionArg.entries.joinToString("") { it.text }
                        else -> versionArg?.text?.removeSurrounding("\"")
                    }
                    fullLength = parent.textRange.length
                    startOffset = parent.textRange.startOffset
                }
            }
        }

        return InstalledPlugin(
            pluginId = pluginId,
            version = version,
            moduleName = moduleName,
            buildFile = filePath,
            offset = startOffset,
            length = fullLength,
            pluginSyntax = PluginSyntax.KOTLIN_SHORTHAND,
            isKotlinShorthand = true,
            isApplied = true
        )
    }

    private fun parseGroovyIdPlugin(call: GrMethodCall, moduleName: String, filePath: String): InstalledPlugin? {
        val args = call.argumentList?.expressionArguments
        if (args.isNullOrEmpty()) return null

        val firstArg = args.first()
        val pluginId = firstArg.text.removeSurrounding("'").removeSurrounding("\"")

        // Look for version in the call chain
        var version: String? = null
        var fullLength = call.textRange.length
        var startOffset = call.textRange.startOffset
        var syntax = PluginSyntax.GROOVY_ID_ONLY

        // In Groovy, version might be in subsequent method calls
        // id 'plugin' version '1.0.0' parses as chained calls
        val callText = call.text
        val versionPattern = Regex("""version\s+['"]([^'"]+)['"]""")
        val versionMatch = versionPattern.find(callText)
        if (versionMatch != null) {
            version = versionMatch.groupValues[1]
            syntax = PluginSyntax.GROOVY_ID_VERSION
        }

        return InstalledPlugin(
            pluginId = pluginId,
            version = version,
            moduleName = moduleName,
            buildFile = filePath,
            offset = startOffset,
            length = fullLength,
            pluginSyntax = syntax,
            isKotlinShorthand = false,
            isApplied = true
        )
    }

    /**
     * Scan a build file for an existing plugin extension block configuration.
     * @param buildFile absolute path to the build file
     * @param extensionName the name of the extension block to find (e.g., "application", "kotlin")
     * @return GradlePluginConfig with parsed properties, or empty config if block not found
     */
    fun scanPluginConfiguration(buildFile: String, extensionName: String): GradlePluginConfig {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile)
            ?: return GradlePluginConfig(extensionName, emptyList(), null, -1, 0)

        val content = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)

        // Find top-level extension block (not nested inside another block)
        val blockPattern = Regex("""(?:^|\n)(\s*)${Regex.escape(extensionName)}\s*\{""")
        var match = blockPattern.find(content)

        // Verify it's top-level by checking brace depth at the match position
        while (match != null) {
            val matchStart = match.range.first
            if (content[matchStart] == '\n') {
                // Adjust to skip the newline
            }
            val braceDepthAtMatch = countBraceDepth(content, 0, matchStart)
            if (braceDepthAtMatch == 0) {
                break
            }
            match = blockPattern.find(content, match.range.last)
        }

        if (match == null) {
            return GradlePluginConfig(extensionName, emptyList(), null, -1, 0)
        }

        val blockStart = match.range.first
        val blockEnd = findBlockEnd(content, blockStart)
        if (blockEnd == -1) {
            return GradlePluginConfig(extensionName, emptyList(), null, -1, 0)
        }

        val blockLength = blockEnd - blockStart + 1
        val blockText = content.substring(blockStart, blockEnd + 1)

        // Extract the content inside the braces
        val openBrace = blockText.indexOf('{')
        val innerContent = if (openBrace != -1) {
            blockText.substring(openBrace + 1, blockText.length - 1)
        } else ""

        // Parse properties from the inner content
        val properties = parseExtensionProperties(innerContent)

        return GradlePluginConfig(
            extensionName = extensionName,
            properties = properties,
            rawText = blockText,
            blockOffset = blockStart,
            blockLength = blockLength
        )
    }

    /**
     * Count brace depth at a position in the text.
     */
    private fun countBraceDepth(text: String, from: Int, to: Int): Int {
        var depth = 0
        for (i in from until to.coerceAtMost(text.length)) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    /**
     * Parse property assignments from an extension block's inner content.
     * Handles both Kotlin DSL (prop = value) and Groovy DSL (prop = value / prop value).
     */
    private fun parseExtensionProperties(content: String): List<ConfigProperty> {
        val properties = mutableListOf<ConfigProperty>()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue

            // Match "property = value" or "property value" patterns
            val assignmentMatch = Regex("""^(\w+)\s*=\s*(.+)$""").find(trimmed)
            if (assignmentMatch != null) {
                val name = assignmentMatch.groupValues[1]
                val rawValue = assignmentMatch.groupValues[2].trim()
                    .removeSurrounding("\"").removeSurrounding("'")
                properties.add(ConfigProperty(name = name, value = rawValue))
                continue
            }

            // Match Groovy-style "property 'value'" or "property \"value\""
            val groovyMatch = Regex("""^(\w+)\s+['"](.+)['"]$""").find(trimmed)
            if (groovyMatch != null) {
                val name = groovyMatch.groupValues[1]
                val value = groovyMatch.groupValues[2]
                properties.add(ConfigProperty(name = name, value = value))
            }
        }

        return properties
    }

    private fun findPluginsBlockStart(text: String, isKotlin: Boolean): Int {
        // Look for "plugins {" or "plugins{"
        val pattern = Regex("""plugins\s*\{""")
        val match = pattern.find(text) ?: return -1
        return match.range.first
    }

    private fun findBlockEnd(text: String, blockStart: Int): Int {
        var braceCount = 0
        var foundFirst = false
        for (i in blockStart until text.length) {
            when (text[i]) {
                '{' -> {
                    braceCount++
                    foundFirst = true
                }
                '}' -> {
                    braceCount--
                    if (foundFirst && braceCount == 0) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun isBuiltInPlugin(name: String): Boolean {
        return name in BUILT_IN_PLUGINS
    }

    companion object {
        /**
         * Common built-in Gradle plugins that can be used without the id() wrapper.
         */
        val BUILT_IN_PLUGINS = setOf(
            "java",
            "java-library",
            "application",
            "groovy",
            "scala",
            "war",
            "ear",
            "maven-publish",
            "ivy-publish",
            "signing",
            "jacoco",
            "checkstyle",
            "pmd",
            "findbugs",
            "codenarc",
            "antlr",
            "idea",
            "eclipse",
            "project-report",
            "build-dashboard",
            "base",
            "distribution"
        )
    }
}
