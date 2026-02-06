package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Handles modifications to Gradle plugin declarations in build files.
 * Supports add, remove, and update operations with preview capability.
 */
class GradlePluginModifier(private val project: Project) {

    /**
     * Generate content with the specified plugin removed.
     * Returns null if the operation cannot be performed.
     */
    fun getRemovedContent(plugin: InstalledPlugin): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(plugin.buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        if (plugin.offset >= 0 && plugin.offset + plugin.length <= text.length) {
            var end = plugin.offset + plugin.length

            // Remove trailing newline to avoid blank lines
            if (end < text.length && text[end] == '\n') {
                end++
            } else if (end < text.length && text[end] == '\r') {
                end++
                if (end < text.length && text[end] == '\n') {
                    end++
                }
            }

            // Also remove leading whitespace on the same line
            var start = plugin.offset
            while (start > 0 && text[start - 1] in " \t") {
                start--
            }

            val sb = StringBuilder(text)
            sb.delete(start, end)
            return sb.toString()
        }
        return null
    }

    /**
     * Generate content with the plugin version updated.
     * Returns null if the operation cannot be performed or if the plugin has no version.
     */
    fun getUpdatedContent(plugin: InstalledPlugin, newVersion: String): String? {
        if (plugin.version == null) return null

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(plugin.buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        if (plugin.offset >= 0 && plugin.offset + plugin.length <= text.length) {
            val currentText = text.substring(plugin.offset, plugin.offset + plugin.length)
            val newText = currentText.replace(plugin.version, newVersion)

            val sb = StringBuilder(text)
            sb.replace(plugin.offset, plugin.offset + plugin.length, newText)
            return sb.toString()
        }
        return null
    }

    /**
     * Generate content with a new plugin added.
     * Detects Kotlin vs Groovy syntax and adds to existing plugins {} block or creates one.
     */
    fun getAddedContent(buildFile: String, pluginId: String, version: String?): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text
        val isKotlin = virtualFile.name.endsWith(".kts")

        val pluginLine = formatPluginLine(pluginId, version, isKotlin)

        // Try to find existing plugins { } block
        val pluginsPattern = Regex("""plugins\s*\{""")
        val pluginsMatch = pluginsPattern.find(text)

        if (pluginsMatch != null) {
            // Find the closing brace of the plugins block
            val blockStart = pluginsMatch.range.first
            var braceCount = 0
            var closingBraceIndex = -1

            for (i in blockStart until text.length) {
                when (text[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            closingBraceIndex = i
                            break
                        }
                    }
                }
            }

            if (closingBraceIndex != -1) {
                val sb = StringBuilder(text)
                // Insert before closing brace with proper indentation
                val prefix = if (text[closingBraceIndex - 1] != '\n') "\n" else ""
                sb.insert(closingBraceIndex, "$prefix    $pluginLine\n")
                return sb.toString()
            }
        }

        // No plugins block found - create one at the beginning of the file
        val pluginsBlock = "plugins {\n    $pluginLine\n}\n\n"

        // Insert after any buildscript {} block if present, otherwise at the start
        val buildscriptPattern = Regex("""buildscript\s*\{""")
        val buildscriptMatch = buildscriptPattern.find(text)

        if (buildscriptMatch != null) {
            val blockStart = buildscriptMatch.range.first
            var braceCount = 0
            var closingBraceIndex = -1

            for (i in blockStart until text.length) {
                when (text[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            closingBraceIndex = i
                            break
                        }
                    }
                }
            }

            if (closingBraceIndex != -1) {
                val insertPos = closingBraceIndex + 1
                val sb = StringBuilder(text)
                sb.insert(insertPos, "\n\n$pluginsBlock")
                return sb.toString()
            }
        }

        // Insert at the very beginning of the file
        return pluginsBlock + text
    }

    /**
     * Format a plugin declaration line based on DSL type.
     */
    private fun formatPluginLine(pluginId: String, version: String?, isKotlin: Boolean): String {
        return if (isKotlin) {
            if (version != null) {
                // Check if it's a kotlin shorthand
                if (pluginId.startsWith("org.jetbrains.kotlin.")) {
                    val shortName = pluginId.removePrefix("org.jetbrains.kotlin.")
                    "kotlin(\"$shortName\") version \"$version\""
                } else {
                    "id(\"$pluginId\") version \"$version\""
                }
            } else {
                if (pluginId.startsWith("org.jetbrains.kotlin.")) {
                    val shortName = pluginId.removePrefix("org.jetbrains.kotlin.")
                    "kotlin(\"$shortName\")"
                } else if (isBuiltInPlugin(pluginId)) {
                    "`$pluginId`"
                } else {
                    "id(\"$pluginId\")"
                }
            }
        } else {
            // Groovy DSL
            if (version != null) {
                "id '$pluginId' version '$version'"
            } else {
                if (isBuiltInPlugin(pluginId)) {
                    pluginId
                } else {
                    "id '$pluginId'"
                }
            }
        }
    }

    /**
     * Check if a plugin ID represents a built-in Gradle plugin.
     */
    private fun isBuiltInPlugin(pluginId: String): Boolean {
        return pluginId in GradlePluginScanner.BUILT_IN_PLUGINS
    }

    /**
     * Generate content with a plugin extension block inserted or replaced.
     * @param buildFile absolute path to the build file
     * @param extensionName the extension block name (e.g., "application", "kotlin")
     * @param properties the properties to set
     * @param existingConfig existing config if already scanned (for in-place replacement)
     * @return the full file content with the new/updated extension block, or null on failure
     */
    fun getConfiguredContent(
        buildFile: String,
        extensionName: String,
        properties: List<ConfigProperty>,
        existingConfig: GradlePluginConfig? = null
    ): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text
        val isKotlin = virtualFile.name.endsWith(".kts")

        val blockDsl = generateExtensionBlock(extensionName, properties, isKotlin)

        // If there's an existing block, replace it in-place
        if (existingConfig != null && existingConfig.blockOffset >= 0) {
            val sb = StringBuilder(text)
            val end = existingConfig.blockOffset + existingConfig.blockLength
            sb.replace(existingConfig.blockOffset, end, blockDsl)
            return sb.toString()
        }

        // No existing block — insert after plugins {} block
        val pluginsPattern = Regex("""plugins\s*\{""")
        val pluginsMatch = pluginsPattern.find(text)

        if (pluginsMatch != null) {
            val blockStart = pluginsMatch.range.first
            var braceCount = 0
            var closingBraceIndex = -1

            for (i in blockStart until text.length) {
                when (text[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            closingBraceIndex = i
                            break
                        }
                    }
                }
            }

            if (closingBraceIndex != -1) {
                val sb = StringBuilder(text)
                sb.insert(closingBraceIndex + 1, "\n\n$blockDsl")
                return sb.toString()
            }
        }

        // Fallback: append at end of file
        return "$text\n\n$blockDsl\n"
    }

    /**
     * Generate a Gradle extension block from properties.
     */
    private fun generateExtensionBlock(
        extensionName: String,
        properties: List<ConfigProperty>,
        isKotlin: Boolean
    ): String {
        return buildString {
            append("$extensionName {\n")
            for (prop in properties) {
                if (prop.value.isNotBlank()) {
                    val formattedValue = formatPropertyValue(prop.value, isKotlin)
                    append("    ${prop.name} = $formattedValue\n")
                }
            }
            append("}")
        }
    }

    /**
     * Format a property value with appropriate quoting.
     */
    private fun formatPropertyValue(value: String, isKotlin: Boolean): String {
        // Booleans and numbers don't need quotes
        if (value == "true" || value == "false") return value
        if (value.toLongOrNull() != null || value.toDoubleOrNull() != null) return value
        // Already quoted or is a method call
        if (value.startsWith("\"") || value.startsWith("'") || value.contains("(")) return value

        return if (isKotlin) "\"$value\"" else "'$value'"
    }

    /**
     * Generate raw extension block content from a raw text string.
     */
    fun getConfiguredContentFromRaw(
        buildFile: String,
        extensionName: String,
        rawBlockContent: String,
        existingConfig: GradlePluginConfig? = null
    ): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        val blockDsl = "$extensionName {\n$rawBlockContent\n}"

        if (existingConfig != null && existingConfig.blockOffset >= 0) {
            val sb = StringBuilder(text)
            val end = existingConfig.blockOffset + existingConfig.blockLength
            sb.replace(existingConfig.blockOffset, end, blockDsl)
            return sb.toString()
        }

        // Insert after plugins {} block
        val pluginsPattern = Regex("""plugins\s*\{""")
        val pluginsMatch = pluginsPattern.find(text)

        if (pluginsMatch != null) {
            val blockStart = pluginsMatch.range.first
            var braceCount = 0
            var closingBraceIndex = -1

            for (i in blockStart until text.length) {
                when (text[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            closingBraceIndex = i
                            break
                        }
                    }
                }
            }

            if (closingBraceIndex != -1) {
                val sb = StringBuilder(text)
                sb.insert(closingBraceIndex + 1, "\n\n$blockDsl")
                return sb.toString()
            }
        }

        return "$text\n\n$blockDsl\n"
    }

    /**
     * Apply changes to a build file with undo support.
     */
    fun applyChanges(buildFile: String, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
            // Save the document immediately so VFS refresh reads the updated content
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    /**
     * Apply changes based on the plugin's build file with undo support.
     */
    fun applyChanges(plugin: InstalledPlugin, newContent: String, commandName: String) {
        applyChanges(plugin.buildFile, newContent, commandName)
    }

    /**
     * Get the original content of a build file (for diff preview).
     */
    fun getOriginalContent(buildFile: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        return document.text
    }
}
