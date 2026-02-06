package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import java.io.File

class GradleDependencyModifier(private val project: Project) {

    fun getRemovedContent(dependency: InstalledDependency): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text
        
        if (dependency.offset >= 0 && dependency.offset + dependency.length <= text.length) {
            var end = dependency.offset + dependency.length
            if (end < text.length && text[end] == '\n') {
                end++
            } else if (end < text.length && text[end] == '\r') {
                end++
                if (end < text.length && text[end] == '\n') {
                    end++
                }
            }
            val sb = StringBuilder(text)
            sb.delete(dependency.offset, end)
            return sb.toString()
        }
        return null
    }

    fun getUpdatedContent(dependency: InstalledDependency, newVersion: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        if (dependency.offset >= 0 && dependency.offset + dependency.length <= text.length) {
            val currentText = text.substring(dependency.offset, dependency.offset + dependency.length)
            val newText = currentText.replace(dependency.version, newVersion)
            val sb = StringBuilder(text)
            sb.replace(dependency.offset, dependency.offset + dependency.length, newText)
            return sb.toString()
        }
        return null
    }

    fun getAddedContent(buildFile: String, groupId: String, artifactId: String, version: String, configuration: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text
        val isKotlin = virtualFile.name.endsWith(".kts")
        
        val depLine = if (isKotlin) {
            "    $configuration(\"$groupId:$artifactId:$version\")"
        } else {
            "    $configuration '$groupId:$artifactId:$version'"
        }

        val dependenciesIndex = text.indexOf("dependencies {")
        if (dependenciesIndex != -1) {
            var braceCount = 0
            var closingBraceIndex = -1
            for (i in dependenciesIndex until text.length) {
                if (text[i] == '{') braceCount++
                if (text[i] == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        closingBraceIndex = i
                        break
                    }
                }
            }
            
            if (closingBraceIndex != -1) {
                val sb = StringBuilder(text)
                // Insert before closing brace, ensure there's a newline
                val prefix = if (text[closingBraceIndex - 1] != '\n') "\n" else ""
                sb.insert(closingBraceIndex, "$prefix$depLine\n")
                return sb.toString()
            }
        }
        
        // If no dependencies block, append one
        return text + "\n\ndependencies {\n$depLine\n}\n"
    }

    /**
     * Get file content with an exclusion added to the given dependency.
     */
    fun getContentWithExclusionAdded(dependency: InstalledDependency, exclusion: DependencyExclusion): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        if (dependency.offset < 0 || dependency.offset + dependency.length > text.length) return null

        val declaration = text.substring(dependency.offset, dependency.offset + dependency.length)
        val isKotlin = virtualFile.name.endsWith(".kts")

        // Detect indentation of the dependency declaration
        val lineStart = text.lastIndexOf('\n', dependency.offset - 1) + 1
        val indent = text.substring(lineStart, dependency.offset)
        val innerIndent = "$indent    "

        val excludeLine = if (isKotlin) {
            if (exclusion.artifactId != null) {
                "${innerIndent}exclude(group = \"${exclusion.groupId}\", module = \"${exclusion.artifactId}\")"
            } else {
                "${innerIndent}exclude(group = \"${exclusion.groupId}\")"
            }
        } else {
            if (exclusion.artifactId != null) {
                "${innerIndent}exclude group: '${exclusion.groupId}', module: '${exclusion.artifactId}'"
            } else {
                "${innerIndent}exclude group: '${exclusion.groupId}'"
            }
        }

        val newDeclaration: String
        // Check if declaration already has a closure/block
        val closingBraceIdx = declaration.lastIndexOf('}')
        if (closingBraceIdx != -1) {
            // Insert new exclude line before closing brace
            val beforeBrace = declaration.substring(0, closingBraceIdx)
            val afterBrace = declaration.substring(closingBraceIdx)
            val prefix = if (beforeBrace.trimEnd().endsWith("{")) "\n" else "\n"
            newDeclaration = "$beforeBrace$prefix$excludeLine\n$indent$afterBrace"
        } else {
            // No closure - wrap with one
            // Remove trailing whitespace from declaration
            val trimmedDecl = declaration.trimEnd()
            newDeclaration = "$trimmedDecl {\n$excludeLine\n$indent}"
        }

        val sb = StringBuilder(text)
        sb.replace(dependency.offset, dependency.offset + dependency.length, newDeclaration)
        return sb.toString()
    }

    /**
     * Get file content with an exclusion removed from the given dependency.
     */
    fun getContentWithExclusionRemoved(dependency: InstalledDependency, exclusion: DependencyExclusion): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.buildFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val text = document.text

        if (dependency.offset < 0 || dependency.offset + dependency.length > text.length) return null

        val declaration = text.substring(dependency.offset, dependency.offset + dependency.length)
        val isKotlin = virtualFile.name.endsWith(".kts")

        // Build a pattern to match the exclude line
        val excludePattern = if (isKotlin) {
            if (exclusion.artifactId != null) {
                Regex("""[ \t]*exclude\(group\s*=\s*"${Regex.escape(exclusion.groupId)}"\s*,\s*module\s*=\s*"${Regex.escape(exclusion.artifactId)}"\)\s*\n?""")
            } else {
                Regex("""[ \t]*exclude\(group\s*=\s*"${Regex.escape(exclusion.groupId)}"\)\s*\n?""")
            }
        } else {
            if (exclusion.artifactId != null) {
                Regex("""[ \t]*exclude\s+group:\s*'${Regex.escape(exclusion.groupId)}'\s*,\s*module:\s*'${Regex.escape(exclusion.artifactId)}'\s*\n?""")
            } else {
                Regex("""[ \t]*exclude\s+group:\s*'${Regex.escape(exclusion.groupId)}'\s*\n?""")
            }
        }

        var newDeclaration = excludePattern.replace(declaration, "")

        // If the closure is now empty (only braces and whitespace), simplify to plain declaration
        val closureMatch = Regex("""\s*\{\s*}""").find(newDeclaration)
        if (closureMatch != null) {
            newDeclaration = newDeclaration.substring(0, closureMatch.range.first)
        }

        val sb = StringBuilder(text)
        sb.replace(dependency.offset, dependency.offset + dependency.length, newDeclaration)
        return sb.toString()
    }

    fun applyChanges(buildFile: String, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
            // Save the document immediately so VFS refresh reads the updated content
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    fun applyChanges(dependency: InstalledDependency, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.buildFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
            // Save the document immediately so VFS refresh reads the updated content
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }
}
