package com.maddrobot.plugins.udm.maven.manager

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Modifies Maven pom.xml files to add, update, or remove plugins.
 */
class MavenPluginModifier(private val project: Project) {

    /**
     * Get content with plugin removed.
     */
    fun getRemovedContent(plugin: MavenInstalledPlugin): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(plugin.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        // Find the full <plugin>...</plugin> block for this plugin
        val pluginRange = findPluginRange(text, plugin.groupId, plugin.artifactId)
        if (pluginRange != null) {
            val (start, end) = pluginRange
            val sb = StringBuilder(text)

            // Also remove leading whitespace on the same line
            var adjustedStart = start
            while (adjustedStart > 0 && text[adjustedStart - 1] in listOf(' ', '\t')) {
                adjustedStart--
            }

            // Also remove trailing whitespace/newline
            var adjustedEnd = end
            while (adjustedEnd < text.length && text[adjustedEnd] in listOf(' ', '\t')) {
                adjustedEnd++
            }
            if (adjustedEnd < text.length && text[adjustedEnd] == '\n') {
                adjustedEnd++
            } else if (adjustedEnd < text.length && text[adjustedEnd] == '\r') {
                adjustedEnd++
                if (adjustedEnd < text.length && text[adjustedEnd] == '\n') {
                    adjustedEnd++
                }
            }

            sb.delete(adjustedStart, adjustedEnd)
            return sb.toString()
        }
        return null
    }

    /**
     * Get content with plugin version updated.
     */
    fun getUpdatedContent(plugin: MavenInstalledPlugin, newVersion: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(plugin.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        // Find the <plugin> block
        val pluginRange = findPluginRange(text, plugin.groupId, plugin.artifactId)
        if (pluginRange != null) {
            val (start, end) = pluginRange
            val pluginText = text.substring(start, end)

            // Find and replace version within this block
            val versionPattern = Regex("<version>([^<]*)</version>")
            val newPluginText = if (versionPattern.containsMatchIn(pluginText)) {
                versionPattern.replace(pluginText) { "<version>$newVersion</version>" }
            } else {
                // No version tag, add one after artifactId
                val artifactIdEnd = pluginText.indexOf("</artifactId>")
                if (artifactIdEnd != -1) {
                    val insertPos = artifactIdEnd + "</artifactId>".length
                    val indent = detectIndent(pluginText)
                    pluginText.substring(0, insertPos) +
                        "\n$indent    <version>$newVersion</version>" +
                        pluginText.substring(insertPos)
                } else {
                    pluginText
                }
            }

            val sb = StringBuilder(text)
            sb.replace(start, end, newPluginText)
            return sb.toString()
        }
        return null
    }

    /**
     * Get content with new plugin added to build/plugins section.
     */
    fun getAddedContent(
        pomFile: String,
        groupId: String,
        artifactId: String,
        version: String?,
        phase: String? = null,
        goals: List<String> = emptyList()
    ): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        // Build the plugin XML
        val indent = "                " // Standard indent for plugins
        val pluginXml = buildPluginXml(groupId, artifactId, version, phase, goals, indent)

        // Try to find existing <build><plugins> section
        val pluginsInsertPoint = findPluginsInsertPoint(text)

        if (pluginsInsertPoint != null) {
            val (insertPos, indentLevel) = pluginsInsertPoint
            val adjustedIndent = "    ".repeat(indentLevel)
            val adjustedPluginXml = pluginXml.replace(indent, adjustedIndent)

            val sb = StringBuilder(text)
            sb.insert(insertPos, "\n$adjustedPluginXml")
            return sb.toString()
        }

        // No <build> section exists, need to create one
        val projectEnd = text.lastIndexOf("</project>")
        if (projectEnd != -1) {
            val buildSection = buildString {
                append("\n    <build>\n")
                append("        <plugins>\n")
                append(pluginXml.replace(indent, "            "))
                append("\n        </plugins>\n")
                append("    </build>\n")
            }

            val sb = StringBuilder(text)
            sb.insert(projectEnd, buildSection)
            return sb.toString()
        }

        return null
    }

    /**
     * Build plugin XML string.
     */
    private fun buildPluginXml(
        groupId: String,
        artifactId: String,
        version: String?,
        phase: String?,
        goals: List<String>,
        indent: String
    ): String {
        return buildString {
            append("$indent<plugin>\n")

            // Only add groupId if it's not the default Maven plugins group
            if (groupId != MavenInstalledPlugin.MAVEN_PLUGINS_GROUP) {
                append("$indent    <groupId>$groupId</groupId>\n")
            }

            append("$indent    <artifactId>$artifactId</artifactId>\n")

            if (version != null) {
                append("$indent    <version>$version</version>\n")
            }

            // Add executions if phase or goals specified
            if (phase != null || goals.isNotEmpty()) {
                append("$indent    <executions>\n")
                append("$indent        <execution>\n")

                if (phase != null) {
                    append("$indent            <phase>$phase</phase>\n")
                }

                if (goals.isNotEmpty()) {
                    append("$indent            <goals>\n")
                    for (goal in goals) {
                        append("$indent                <goal>$goal</goal>\n")
                    }
                    append("$indent            </goals>\n")
                }

                append("$indent        </execution>\n")
                append("$indent    </executions>\n")
            }

            append("$indent</plugin>")
        }
    }

    /**
     * Find the insertion point for a new plugin.
     * Returns Pair(offset, indentLevel) or null if not found.
     */
    private fun findPluginsInsertPoint(text: String): Pair<Int, Int>? {
        // Look for </plugins> within <build> section
        val buildStart = text.indexOf("<build>")
        if (buildStart == -1) return null

        // Find the <plugins> section within build
        val pluginsStart = text.indexOf("<plugins>", buildStart)
        if (pluginsStart == -1) {
            // No plugins section, find </build> and add plugins before it
            val buildEnd = text.indexOf("</build>", buildStart)
            if (buildEnd != -1) {
                // Return position just before </build>
                return Pair(buildEnd, 2) // indent level 2 for plugins
            }
            return null
        }

        // Find </plugins>
        val pluginsEnd = text.indexOf("</plugins>", pluginsStart)
        if (pluginsEnd == -1) return null

        // Determine indent level based on position
        val beforePluginsEnd = text.substring(0, pluginsEnd)
        val lastNewline = beforePluginsEnd.lastIndexOf('\n')
        val indentChars = if (lastNewline != -1) {
            pluginsEnd - lastNewline - 1
        } else {
            0
        }
        val indentLevel = (indentChars / 4) + 1

        return Pair(pluginsEnd, indentLevel)
    }

    /**
     * Apply changes to the pom.xml file.
     */
    fun applyChanges(pomFile: String, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
        })
    }

    /**
     * Apply changes for a specific plugin.
     */
    fun applyChanges(plugin: MavenInstalledPlugin, newContent: String, commandName: String) {
        applyChanges(plugin.pomFile, newContent, commandName)
    }

    /**
     * Get content with <configuration> block inserted or replaced within a plugin.
     * Returns the full file content with the updated configuration, or null on failure.
     */
    fun getConfiguredContent(plugin: MavenInstalledPlugin, configuration: Map<String, String>): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(plugin.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        val pluginRange = findPluginRange(text, plugin.groupId, plugin.artifactId) ?: return null
        val (start, end) = pluginRange
        val pluginText = text.substring(start, end)

        // Build new <configuration> XML
        val indent = detectIndent(pluginText)
        val configXml = buildConfigurationXml(configuration, "$indent    ")

        // Check if there's an existing <configuration> block
        val configStartPattern = Regex("""<configuration\s*>""")
        val configStartMatch = configStartPattern.find(pluginText)

        val newPluginText = if (configStartMatch != null) {
            // Replace existing configuration block
            val configEndIndex = pluginText.indexOf("</configuration>", configStartMatch.range.first)
            if (configEndIndex != -1) {
                val fullConfigEnd = configEndIndex + "</configuration>".length
                pluginText.substring(0, configStartMatch.range.first) +
                    configXml +
                    pluginText.substring(fullConfigEnd)
            } else {
                pluginText // Malformed XML, don't modify
            }
        } else {
            // Insert configuration block after </version> or </artifactId>
            val versionEnd = pluginText.indexOf("</version>")
            val artifactIdEnd = pluginText.indexOf("</artifactId>")

            val insertAfterTag = when {
                versionEnd != -1 -> "</version>"
                artifactIdEnd != -1 -> "</artifactId>"
                else -> null
            }

            if (insertAfterTag != null) {
                val insertPos = pluginText.indexOf(insertAfterTag) + insertAfterTag.length
                pluginText.substring(0, insertPos) +
                    "\n$configXml" +
                    pluginText.substring(insertPos)
            } else {
                pluginText
            }
        }

        val sb = StringBuilder(text)
        sb.replace(start, end, newPluginText)
        return sb.toString()
    }

    /**
     * Build a <configuration> XML block from key-value pairs.
     */
    private fun buildConfigurationXml(configuration: Map<String, String>, indent: String): String {
        if (configuration.isEmpty()) return ""

        return buildString {
            append("$indent<configuration>\n")
            for ((key, value) in configuration) {
                append("$indent    <$key>$value</$key>\n")
            }
            append("$indent</configuration>")
        }
    }

    /**
     * Get the original content of a pom file (for diff preview).
     */
    fun getOriginalContent(pomFile: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        return document.text
    }

    /**
     * Find the start and end offset of a <plugin> block in the text.
     */
    private fun findPluginRange(text: String, groupId: String, artifactId: String): Pair<Int, Int>? {
        // Find all <plugin> blocks
        var searchStart = 0
        while (true) {
            val pluginStart = text.indexOf("<plugin>", searchStart)
            if (pluginStart == -1) break

            val pluginEnd = text.indexOf("</plugin>", pluginStart)
            if (pluginEnd == -1) break

            val fullEnd = pluginEnd + "</plugin>".length
            val pluginBlock = text.substring(pluginStart, fullEnd)

            // Check if this block matches our groupId and artifactId
            val hasArtifactId = pluginBlock.contains("<artifactId>$artifactId</artifactId>") ||
                Regex("<artifactId>\\s*${Regex.escape(artifactId)}\\s*</artifactId>").containsMatchIn(pluginBlock)

            if (!hasArtifactId) {
                searchStart = fullEnd
                continue
            }

            // Check groupId - if it's the default Maven plugins group, it may be omitted
            val hasGroupId = if (groupId == MavenInstalledPlugin.MAVEN_PLUGINS_GROUP) {
                // Default group - plugin matches if no groupId specified OR if specified matches
                !pluginBlock.contains("<groupId>") ||
                    pluginBlock.contains("<groupId>$groupId</groupId>") ||
                    Regex("<groupId>\\s*${Regex.escape(groupId)}\\s*</groupId>").containsMatchIn(pluginBlock)
            } else {
                pluginBlock.contains("<groupId>$groupId</groupId>") ||
                    Regex("<groupId>\\s*${Regex.escape(groupId)}\\s*</groupId>").containsMatchIn(pluginBlock)
            }

            if (hasGroupId) {
                return Pair(pluginStart, fullEnd)
            }

            searchStart = fullEnd
        }

        return null
    }

    private fun detectIndent(text: String): String {
        // Find first line that has content
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.isNotEmpty() && trimmed.startsWith("<")) {
                val indent = line.substring(0, line.length - trimmed.length)
                return indent
            }
        }
        return "    "
    }
}
