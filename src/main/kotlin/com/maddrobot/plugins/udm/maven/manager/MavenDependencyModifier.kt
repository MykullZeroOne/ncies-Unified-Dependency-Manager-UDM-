package com.maddrobot.plugins.udm.maven.manager

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion

/**
 * Modifies Maven pom.xml files to add, update, or remove dependencies.
 */
class MavenDependencyModifier(private val project: Project) {

    /**
     * Get content with dependency removed.
     */
    fun getRemovedContent(dependency: MavenInstalledDependency): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        // Find the full <dependency>...</dependency> block for this dependency
        val depRange = findDependencyRange(text, dependency.groupId, dependency.artifactId)
        if (depRange != null) {
            val (start, end) = depRange
            val sb = StringBuilder(text)
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
            sb.delete(start, adjustedEnd)
            return sb.toString()
        }
        return null
    }

    /**
     * Get content with dependency version updated.
     */
    fun getUpdatedContent(dependency: MavenInstalledDependency, newVersion: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        // Find the <dependency> block
        val depRange = findDependencyRange(text, dependency.groupId, dependency.artifactId)
        if (depRange != null) {
            val (start, end) = depRange
            val depText = text.substring(start, end)

            // Find and replace version within this block
            val versionPattern = Regex("<version>([^<]*)</version>")
            val newDepText = if (versionPattern.containsMatchIn(depText)) {
                versionPattern.replace(depText) { "<version>$newVersion</version>" }
            } else {
                // No version tag, add one after artifactId
                val artifactIdEnd = depText.indexOf("</artifactId>")
                if (artifactIdEnd != -1) {
                    val insertPos = artifactIdEnd + "</artifactId>".length
                    val indent = detectIndent(depText)
                    depText.substring(0, insertPos) + "\n$indent    <version>$newVersion</version>" + depText.substring(insertPos)
                } else {
                    depText
                }
            }

            val sb = StringBuilder(text)
            sb.replace(start, end, newDepText)
            return sb.toString()
        }
        return null
    }

    /**
     * Get content with new dependency added.
     */
    fun getAddedContent(pomFile: String, groupId: String, artifactId: String, version: String, scope: String?): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        // Build the dependency XML
        val indent = "        " // Standard 8-space indent for dependencies
        val depXml = buildString {
            append("$indent<dependency>\n")
            append("$indent    <groupId>$groupId</groupId>\n")
            append("$indent    <artifactId>$artifactId</artifactId>\n")
            append("$indent    <version>$version</version>\n")
            if (scope != null && scope != "compile") {
                append("$indent    <scope>$scope</scope>\n")
            }
            append("$indent</dependency>")
        }

        // Find <dependencies> block
        val dependenciesStart = text.indexOf("<dependencies>")
        if (dependenciesStart != -1) {
            val dependenciesEnd = text.indexOf("</dependencies>", dependenciesStart)
            if (dependenciesEnd != -1) {
                val sb = StringBuilder(text)
                // Insert before </dependencies>
                val insertPos = dependenciesEnd
                sb.insert(insertPos, "$depXml\n    ")
                return sb.toString()
            }
        }

        // No <dependencies> block, need to add one
        // Find </project> and add before it
        val projectEnd = text.lastIndexOf("</project>")
        if (projectEnd != -1) {
            val sb = StringBuilder(text)
            val dependenciesBlock = "\n    <dependencies>\n$depXml\n    </dependencies>\n"
            sb.insert(projectEnd, dependenciesBlock)
            return sb.toString()
        }

        return null
    }

    /**
     * Get content with an exclusion added to the given dependency.
     */
    fun getContentWithExclusionAdded(dependency: MavenInstalledDependency, exclusion: DependencyExclusion): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        val depRange = findDependencyRange(text, dependency.groupId, dependency.artifactId) ?: return null
        val (start, end) = depRange
        val depBlock = text.substring(start, end)
        val indent = detectIndent(depBlock)
        val innerIndent = "$indent    "
        val exclInnerIndent = "$innerIndent    "

        val exclusionXml = buildString {
            append("$exclInnerIndent<exclusion>\n")
            append("$exclInnerIndent    <groupId>${exclusion.groupId}</groupId>\n")
            if (exclusion.artifactId != null) {
                append("$exclInnerIndent    <artifactId>${exclusion.artifactId}</artifactId>\n")
            } else {
                append("$exclInnerIndent    <artifactId>*</artifactId>\n")
            }
            append("$exclInnerIndent</exclusion>")
        }

        val newDepBlock: String
        val exclusionsEndTag = "</exclusions>"
        val exclusionsStartIdx = depBlock.indexOf("<exclusions>")

        if (exclusionsStartIdx != -1) {
            // Existing <exclusions> block — insert before </exclusions>
            val exclusionsEndIdx = depBlock.indexOf(exclusionsEndTag, exclusionsStartIdx)
            if (exclusionsEndIdx == -1) return null
            val before = depBlock.substring(0, exclusionsEndIdx)
            val after = depBlock.substring(exclusionsEndIdx)
            newDepBlock = "$before$exclusionXml\n$innerIndent$after"
        } else {
            // No <exclusions> block — insert before </dependency>
            val closingTag = "</dependency>"
            val closingIdx = depBlock.indexOf(closingTag)
            if (closingIdx == -1) return null
            val before = depBlock.substring(0, closingIdx)
            val after = depBlock.substring(closingIdx)
            val exclusionsBlock = "${innerIndent}<exclusions>\n$exclusionXml\n$innerIndent</exclusions>\n$indent"
            newDepBlock = "$before$exclusionsBlock$after"
        }

        val sb = StringBuilder(text)
        sb.replace(start, end, newDepBlock)
        return sb.toString()
    }

    /**
     * Get content with an exclusion removed from the given dependency.
     */
    fun getContentWithExclusionRemoved(dependency: MavenInstalledDependency, exclusion: DependencyExclusion): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.pomFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        val depRange = findDependencyRange(text, dependency.groupId, dependency.artifactId) ?: return null
        val (start, end) = depRange
        val depBlock = text.substring(start, end)

        // Find and remove the matching <exclusion> element
        val artifactPattern = if (exclusion.artifactId != null) {
            Regex.escape(exclusion.artifactId)
        } else {
            "\\*"
        }
        val exclusionPattern = Regex(
            """[ \t]*<exclusion>\s*<groupId>\s*${Regex.escape(exclusion.groupId)}\s*</groupId>\s*<artifactId>\s*$artifactPattern\s*</artifactId>\s*</exclusion>\s*\n?""",
            RegexOption.DOT_MATCHES_ALL
        )

        var newDepBlock = exclusionPattern.replace(depBlock, "")

        // If <exclusions> block is now empty, remove it entirely
        val emptyExclusionsPattern = Regex("""[ \t]*<exclusions>\s*</exclusions>\s*\n?""", RegexOption.DOT_MATCHES_ALL)
        newDepBlock = emptyExclusionsPattern.replace(newDepBlock, "")

        val sb = StringBuilder(text)
        sb.replace(start, end, newDepBlock)
        return sb.toString()
    }

    /**
     * Apply changes to the pom.xml file.
     */
    fun applyChanges(pomFile: String, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
            // Save the document immediately so VFS refresh reads the updated content
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    /**
     * Apply changes for a specific dependency.
     */
    fun applyChanges(dependency: MavenInstalledDependency, newContent: String, commandName: String) {
        applyChanges(dependency.pomFile, newContent, commandName)
    }

    /**
     * Find the start and end offset of a <dependency> block in the text.
     */
    private fun findDependencyRange(text: String, groupId: String, artifactId: String): Pair<Int, Int>? {
        // Find all <dependency> blocks
        var searchStart = 0
        while (true) {
            val depStart = text.indexOf("<dependency>", searchStart)
            if (depStart == -1) break

            val depEnd = text.indexOf("</dependency>", depStart)
            if (depEnd == -1) break

            val fullEnd = depEnd + "</dependency>".length
            val depBlock = text.substring(depStart, fullEnd)

            // Check if this block matches our groupId and artifactId
            if (depBlock.contains("<groupId>$groupId</groupId>") &&
                depBlock.contains("<artifactId>$artifactId</artifactId>")) {
                return Pair(depStart, fullEnd)
            }

            // Also check with whitespace variations
            val groupIdPattern = Regex("<groupId>\\s*${Regex.escape(groupId)}\\s*</groupId>")
            val artifactIdPattern = Regex("<artifactId>\\s*${Regex.escape(artifactId)}\\s*</artifactId>")

            if (groupIdPattern.containsMatchIn(depBlock) && artifactIdPattern.containsMatchIn(depBlock)) {
                return Pair(depStart, fullEnd)
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
