package star.intellijplugin.pkgfinder.gradle.manager

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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

    fun applyChanges(buildFile: String, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
        })
    }

    fun applyChanges(dependency: InstalledDependency, newContent: String, commandName: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(dependency.buildFile) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
        })
    }
}
