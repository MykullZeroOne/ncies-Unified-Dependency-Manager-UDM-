package star.intellijplugin.pkgfinder.maven.manager

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Scans Maven pom.xml files to extract installed dependencies.
 */
class MavenDependencyScanner(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    /**
     * Scan all pom.xml files in the project and return installed dependencies.
     */
    fun scanInstalledDependencies(): List<MavenInstalledDependency> {
        return ReadAction.compute<List<MavenInstalledDependency>, Exception> {
            val dependencies = mutableListOf<MavenInstalledDependency>()
            val pomFiles = findPomFiles()

            for (pomFile in pomFiles) {
                try {
                    val pomDeps = parsePomFile(pomFile)
                    dependencies.addAll(pomDeps)
                } catch (e: Exception) {
                    log.warn("Failed to parse pom.xml: ${pomFile.path}", e)
                }
            }

            dependencies
        }
    }

    /**
     * Get a map of module names to their pom.xml files.
     */
    fun getModulePomFiles(): Map<String, VirtualFile> {
        return ReadAction.compute<Map<String, VirtualFile>, Exception> {
            val result = mutableMapOf<String, VirtualFile>()
            findPomFiles().forEach { file ->
                val moduleName = file.parent?.name ?: "root"
                result[moduleName] = file
            }
            result
        }
    }

    /**
     * Check if this project is a Maven project.
     */
    fun isMavenProject(): Boolean {
        return ReadAction.compute<Boolean, Exception> {
            val basePath = project.basePath ?: return@compute false
            val projectRoot = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@compute false
            projectRoot.findChild("pom.xml") != null
        }
    }

    private fun findPomFiles(): List<VirtualFile> {
        val pomFiles = mutableListOf<VirtualFile>()
        val basePath = project.basePath ?: return emptyList()
        val projectRoot = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()

        fun collect(file: VirtualFile) {
            if (file.isDirectory) {
                // Skip common non-project directories
                if (file.name in listOf("target", ".idea", "node_modules", ".git", "build")) return
                file.children.forEach { collect(it) }
            } else {
                if (file.name == "pom.xml") {
                    pomFiles.add(file)
                }
            }
        }
        collect(projectRoot)
        return pomFiles
    }

    private fun parsePomFile(pomFile: VirtualFile): List<MavenInstalledDependency> {
        val result = mutableListOf<MavenInstalledDependency>()
        val moduleName = pomFile.parent?.name ?: "root"
        val filePath = pomFile.path

        val content = String(pomFile.contentsToByteArray(), Charsets.UTF_8)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(content.byteInputStream())

        // Build properties map for variable resolution
        val properties = extractProperties(doc)

        // Find the main dependencies block (not under dependencyManagement)
        val dependenciesElements = doc.getElementsByTagName("dependencies")
        for (i in 0 until dependenciesElements.length) {
            val depsElement = dependenciesElements.item(i)
            val parent = depsElement.parentNode

            // Skip if this is under dependencyManagement
            if (parent?.nodeName == "dependencyManagement") continue

            // Only process if parent is project (root dependencies)
            if (parent?.nodeName == "project") {
                val depElements = (depsElement as Element).getElementsByTagName("dependency")
                for (j in 0 until depElements.length) {
                    val depElement = depElements.item(j) as Element

                    // Only process direct children
                    if (depElement.parentNode != depsElement) continue

                    val dependency = parseDependencyElement(depElement, properties, moduleName, filePath, content)
                    if (dependency != null) {
                        result.add(dependency)
                    }
                }
            }
        }

        return result
    }

    private fun parseDependencyElement(
        element: Element,
        properties: Map<String, String>,
        moduleName: String,
        filePath: String,
        content: String
    ): MavenInstalledDependency? {
        val groupId = resolveProperty(getChildText(element, "groupId"), properties) ?: return null
        val artifactId = resolveProperty(getChildText(element, "artifactId"), properties) ?: return null
        val version = resolveProperty(getChildText(element, "version"), properties)
        val scope = getChildText(element, "scope") ?: "compile"
        val optional = getChildText(element, "optional")?.toBoolean() ?: false

        // Calculate offset in the original file for editing
        // Find the position of this dependency in the content
        val offset = findDependencyOffset(content, groupId, artifactId)

        return MavenInstalledDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = version ?: "managed", // version may come from dependencyManagement
            scope = scope,
            optional = optional,
            moduleName = moduleName,
            pomFile = filePath,
            offset = offset,
            length = 0 // Will be calculated when needed for editing
        )
    }

    private fun extractProperties(doc: Document): Map<String, String> {
        val properties = mutableMapOf<String, String>()

        // Get project version and other standard properties
        val projectElement = doc.documentElement
        getDirectChildText(projectElement, "version")?.let { properties["project.version"] = it }
        getDirectChildText(projectElement, "groupId")?.let { properties["project.groupId"] = it }
        getDirectChildText(projectElement, "artifactId")?.let { properties["project.artifactId"] = it }

        // Get parent version if no project version
        val parentElements = doc.getElementsByTagName("parent")
        if (parentElements.length > 0) {
            val parent = parentElements.item(0) as Element
            if (!properties.containsKey("project.version")) {
                getChildText(parent, "version")?.let { properties["project.version"] = it }
            }
            if (!properties.containsKey("project.groupId")) {
                getChildText(parent, "groupId")?.let { properties["project.groupId"] = it }
            }
        }

        // Get custom properties
        val propertiesElements = doc.getElementsByTagName("properties")
        if (propertiesElements.length > 0) {
            val propsElement = propertiesElements.item(0)
            val children = propsElement.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val value = child.textContent?.trim()
                    if (value != null) {
                        properties[child.nodeName] = value
                    }
                }
            }
        }

        return properties
    }

    private fun resolveProperty(value: String?, properties: Map<String, String>): String? {
        if (value == null) return null

        var result: String = value
        val propertyPattern = Regex("\\$\\{([^}]+)\\}")

        var iterations = 0
        while (propertyPattern.containsMatchIn(result) && iterations < 10) {
            result = propertyPattern.replace(result) { matchResult ->
                val propertyName = matchResult.groupValues[1]
                properties[propertyName] ?: matchResult.value
            }
            iterations++
        }

        return result
    }

    private fun getChildText(element: Element, tagName: String): String? {
        val nodes = element.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            // Get the first direct child with this tag name
            for (i in 0 until nodes.length) {
                if (nodes.item(i).parentNode == element) {
                    return nodes.item(i).textContent?.trim()
                }
            }
        }
        return null
    }

    private fun getDirectChildText(element: Element, tagName: String): String? {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                return child.textContent?.trim()
            }
        }
        return null
    }

    private fun findDependencyOffset(content: String, groupId: String, artifactId: String): Int {
        // Simple heuristic: find the <groupId>...</groupId> that matches
        val pattern = "<groupId>\\s*${Regex.escape(groupId)}\\s*</groupId>"
        val regex = Regex(pattern)
        val matches = regex.findAll(content)

        for (match in matches) {
            // Check if artifactId follows within reasonable distance
            val searchStart = match.range.last
            val searchEnd = minOf(searchStart + 200, content.length)
            val afterGroupId = content.substring(searchStart, searchEnd)

            if (afterGroupId.contains("<artifactId>$artifactId</artifactId>") ||
                afterGroupId.contains("<artifactId>\n") && afterGroupId.contains(artifactId)) {
                // Find the <dependency> tag that contains this
                val beforeMatch = content.substring(0, match.range.first)
                val lastDepStart = beforeMatch.lastIndexOf("<dependency>")
                if (lastDepStart != -1) {
                    return lastDepStart
                }
            }
        }

        return -1
    }
}

/**
 * Represents a dependency installed in a Maven project.
 */
data class MavenInstalledDependency(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val scope: String,
    val optional: Boolean = false,
    val moduleName: String,
    val pomFile: String,
    val offset: Int = -1,
    val length: Int = 0
) {
    val id: String get() = "$groupId:$artifactId"
    val fullName: String get() = "$groupId:$artifactId:$version"
}
