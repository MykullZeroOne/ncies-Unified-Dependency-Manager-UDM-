package com.maddrobot.plugins.udm.maven.manager

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
 * Scans Maven pom.xml files to extract installed plugins from build/plugins sections.
 */
class MavenPluginScanner(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    /**
     * Scan all pom.xml files in the project and return installed plugins.
     */
    fun scanInstalledPlugins(): List<MavenInstalledPlugin> {
        return ReadAction.compute<List<MavenInstalledPlugin>, Exception> {
            val plugins = mutableListOf<MavenInstalledPlugin>()
            val pomFiles = findPomFiles()

            for (pomFile in pomFiles) {
                try {
                    val pomPlugins = parsePomFile(pomFile)
                    plugins.addAll(pomPlugins)
                } catch (e: Exception) {
                    log.warn("Failed to parse pom.xml for plugins: ${pomFile.path}", e)
                }
            }

            plugins
        }
    }

    /**
     * Get a map of module names to their pom.xml files.
     */
    fun getModulePomFiles(): Map<String, VirtualFile> {
        return ReadAction.compute<Map<String, VirtualFile>, Exception> {
            val result = mutableMapOf<String, VirtualFile>()
            findPomFiles().forEach { file ->
                val moduleName = getModuleName(file)
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

    private fun getModuleName(file: VirtualFile): String {
        val basePath = project.basePath
        if (basePath != null && file.parent?.path == basePath) {
            return "root"
        }
        return file.parent?.name ?: "root"
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

    private fun parsePomFile(pomFile: VirtualFile): List<MavenInstalledPlugin> {
        val result = mutableListOf<MavenInstalledPlugin>()
        val moduleName = getModuleName(pomFile)
        val filePath = pomFile.path

        val content = String(pomFile.contentsToByteArray(), Charsets.UTF_8)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(content.byteInputStream())

        // Build properties map for variable resolution
        val properties = extractProperties(doc)

        // Find plugins in build/plugins section
        val buildElements = doc.getElementsByTagName("build")
        for (i in 0 until buildElements.length) {
            val buildElement = buildElements.item(i) as Element
            val parent = buildElement.parentNode

            // Only process if parent is project (root build section)
            if (parent?.nodeName != "project") continue

            // Find plugins section
            val pluginsElements = buildElement.getElementsByTagName("plugins")
            for (j in 0 until pluginsElements.length) {
                val pluginsElement = pluginsElements.item(j) as Element

                // Check if this is under pluginManagement
                val isPluginManagement = isUnderPluginManagement(pluginsElement)

                // Get direct child plugin elements
                val pluginElements = pluginsElement.getElementsByTagName("plugin")
                for (k in 0 until pluginElements.length) {
                    val pluginElement = pluginElements.item(k) as Element

                    // Only process direct children
                    if (pluginElement.parentNode != pluginsElement) continue

                    val plugin = parsePluginElement(
                        pluginElement,
                        properties,
                        moduleName,
                        filePath,
                        content,
                        isPluginManagement
                    )
                    if (plugin != null) {
                        result.add(plugin)
                    }
                }
            }
        }

        return result
    }

    private fun isUnderPluginManagement(element: Element): Boolean {
        var parent = element.parentNode
        while (parent != null) {
            if (parent.nodeName == "pluginManagement") {
                return true
            }
            parent = parent.parentNode
        }
        return false
    }

    private fun parsePluginElement(
        element: Element,
        properties: Map<String, String>,
        moduleName: String,
        filePath: String,
        content: String,
        isFromPluginManagement: Boolean
    ): MavenInstalledPlugin? {
        val groupId = resolveProperty(getChildText(element, "groupId"), properties)
            ?: MavenInstalledPlugin.MAVEN_PLUGINS_GROUP  // Default Maven plugins group
        val artifactId = resolveProperty(getChildText(element, "artifactId"), properties) ?: return null
        val version = resolveProperty(getChildText(element, "version"), properties)
        val inherited = getChildText(element, "inherited")?.toBoolean() ?: true

        // Extract execution phase and goals if present
        var phase: String? = null
        val goals = mutableListOf<String>()

        val executionsElements = element.getElementsByTagName("executions")
        if (executionsElements.length > 0) {
            val executionsElement = executionsElements.item(0) as Element
            val executionElements = executionsElement.getElementsByTagName("execution")
            if (executionElements.length > 0) {
                val firstExecution = executionElements.item(0) as Element
                phase = getChildText(firstExecution, "phase")

                val goalsElements = firstExecution.getElementsByTagName("goals")
                if (goalsElements.length > 0) {
                    val goalsElement = goalsElements.item(0) as Element
                    val goalElements = goalsElement.getElementsByTagName("goal")
                    for (i in 0 until goalElements.length) {
                        val goalText = goalElements.item(i).textContent?.trim()
                        if (!goalText.isNullOrBlank()) {
                            goals.add(goalText)
                        }
                    }
                }
            }
        }

        // Extract <configuration> block as flat key-value map
        val configuration = extractConfiguration(element)

        // Calculate offset in the original file for editing
        val offset = findPluginOffset(content, groupId, artifactId)
        val length = if (offset >= 0) findPluginLength(content, offset) else 0

        return MavenInstalledPlugin(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            moduleName = moduleName,
            pomFile = filePath,
            offset = offset,
            length = length,
            phase = phase,
            goals = goals,
            inherited = inherited,
            isFromPluginManagement = isFromPluginManagement,
            configuration = configuration
        )
    }

    /**
     * Extract <configuration> block from a plugin element as a flat key-value map.
     * Only handles direct child elements (flat key-value pairs).
     */
    private fun extractConfiguration(pluginElement: Element): Map<String, String> {
        val config = mutableMapOf<String, String>()
        val children = pluginElement.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == "configuration") {
                val configElement = child as Element
                val configChildren = configElement.childNodes
                for (j in 0 until configChildren.length) {
                    val configChild = configChildren.item(j)
                    if (configChild.nodeType == Node.ELEMENT_NODE) {
                        val value = configChild.textContent?.trim() ?: ""
                        config[configChild.nodeName] = value
                    }
                }
                break // Only process the first <configuration> found at plugin level
            }
        }
        return config
    }

    /**
     * Resolve property references in a version string using properties from a pom.xml file.
     * This is useful when a version like "${maven-compiler-plugin.version}" needs to be
     * resolved to the actual version number by reading the pom's <properties> block.
     *
     * @param version The version string that may contain ${...} property references
     * @param pomFilePath Path to the pom.xml file to read properties from
     * @return The resolved version, or null if resolution fails or properties not found
     */
    fun resolveVersionFromPom(version: String, pomFilePath: String): String? {
        if (!version.contains("\${")) return version

        return try {
            val content = java.io.File(pomFilePath).readText(Charsets.UTF_8)
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(content.byteInputStream())
            val properties = extractProperties(doc)
            val resolved = resolveProperty(version, properties)
            // Only return if fully resolved (no remaining ${...})
            if (resolved != null && !resolved.contains("\${")) resolved else null
        } catch (e: Exception) {
            log.debug("Failed to resolve version from pom: ${e.message}")
            null
        }
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

    private fun findPluginOffset(content: String, groupId: String, artifactId: String): Int {
        // Find the <plugin> tag that contains this groupId and artifactId
        val pluginPattern = Regex("<plugin>")
        val matches = pluginPattern.findAll(content)

        for (match in matches) {
            val pluginStart = match.range.first
            val pluginEnd = content.indexOf("</plugin>", pluginStart)
            if (pluginEnd == -1) continue

            val pluginContent = content.substring(pluginStart, pluginEnd + "</plugin>".length)

            // Check if this plugin has our groupId and artifactId
            val hasGroupId = groupId == MavenInstalledPlugin.MAVEN_PLUGINS_GROUP ||
                pluginContent.contains("<groupId>$groupId</groupId>") ||
                pluginContent.contains("<groupId>\n") && pluginContent.contains(groupId)

            val hasArtifactId = pluginContent.contains("<artifactId>$artifactId</artifactId>") ||
                pluginContent.contains("<artifactId>\n") && pluginContent.contains(artifactId)

            if (hasArtifactId && (hasGroupId || !pluginContent.contains("<groupId>"))) {
                return pluginStart
            }
        }

        return -1
    }

    private fun findPluginLength(content: String, offset: Int): Int {
        val endTag = "</plugin>"
        val endIndex = content.indexOf(endTag, offset)
        if (endIndex == -1) return 0
        return endIndex + endTag.length - offset
    }
}
