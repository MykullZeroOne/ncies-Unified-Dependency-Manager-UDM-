package com.maddrobot.plugins.udm.maven.manager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service that downloads Maven plugin JARs, extracts META-INF/maven/plugin.xml,
 * and parses mojo descriptors for the plugin configuration editor.
 */
@Service(Service.Level.PROJECT)
class PluginDescriptorService(private val project: Project) {

    private val log = Logger.getInstance(javaClass)

    private data class CachedDescriptor(
        val descriptor: PluginDescriptor?,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CachedDescriptor>()
    private val CACHE_TTL_MS = 3600_000L // 1 hour

    companion object {
        fun getInstance(project: Project): PluginDescriptorService =
            project.getService(PluginDescriptorService::class.java)

        private const val MAVEN_CENTRAL_JAR_URL = "https://repo.maven.org/maven2"
        private const val TIMEOUT_MS = 15_000
    }

    /**
     * Get the plugin descriptor synchronously. Returns cached value if available.
     */
    fun getPluginDescriptor(groupId: String, artifactId: String, version: String): PluginDescriptor? {
        val key = "$groupId:$artifactId:$version"

        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.descriptor
        }

        val descriptor = fetchPluginDescriptor(groupId, artifactId, version)
        cache[key] = CachedDescriptor(descriptor, System.currentTimeMillis())
        return descriptor
    }

    /**
     * Get the plugin descriptor asynchronously, invoking the callback on the EDT.
     */
    fun getPluginDescriptorAsync(
        groupId: String,
        artifactId: String,
        version: String,
        callback: (PluginDescriptor?) -> Unit
    ) {
        val key = "$groupId:$artifactId:$version"

        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            callback(cached.descriptor)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val descriptor = fetchPluginDescriptor(groupId, artifactId, version)
            cache[key] = CachedDescriptor(descriptor, System.currentTimeMillis())

            ApplicationManager.getApplication().invokeLater {
                callback(descriptor)
            }
        }
    }

    private fun fetchPluginDescriptor(groupId: String, artifactId: String, version: String): PluginDescriptor? {
        return try {
            val groupPath = groupId.replace('.', '/')
            val jarUrl = "$MAVEN_CENTRAL_JAR_URL/$groupPath/$artifactId/$version/$artifactId-$version.jar"

            log.debug("Fetching plugin descriptor from: $jarUrl")

            val url = URI(jarUrl).toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "UDM-Plugin/1.0")

            if (connection.responseCode != 200) {
                log.debug("Failed to download plugin JAR: HTTP ${connection.responseCode}")
                return null
            }

            val pluginXml = extractPluginXmlFromStream(connection.inputStream)
            connection.disconnect()

            if (pluginXml != null) {
                parsePluginXml(pluginXml, groupId, artifactId, version)
            } else {
                log.debug("No META-INF/maven/plugin.xml found in JAR")
                null
            }
        } catch (e: Exception) {
            log.debug("Failed to fetch plugin descriptor for $groupId:$artifactId:$version: ${e.message}")
            null
        }
    }

    /**
     * Stream the ZIP and extract META-INF/maven/plugin.xml without loading the entire JAR.
     */
    private fun extractPluginXmlFromStream(input: InputStream): ByteArray? {
        val zis = ZipInputStream(input)
        try {
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "META-INF/maven/plugin.xml") {
                    return zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } finally {
            zis.close()
        }
        return null
    }

    private fun parsePluginXml(xmlBytes: ByteArray, groupId: String, artifactId: String, version: String): PluginDescriptor? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlBytes.inputStream())

            val root = doc.documentElement

            val goalPrefix = getChildText(root, "goalPrefix")
            val description = getChildText(root, "description")
            val url = getChildText(root, "url")

            val mojos = mutableListOf<MojoDescriptor>()
            val mojosElement = getChildElement(root, "mojos")
            if (mojosElement != null) {
                val mojoElements = mojosElement.getElementsByTagName("mojo")
                for (i in 0 until mojoElements.length) {
                    val mojoElement = mojoElements.item(i) as? Element ?: continue
                    if (mojoElement.parentNode != mojosElement) continue
                    parseMojo(mojoElement)?.let { mojos.add(it) }
                }
            }

            PluginDescriptor(
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                goalPrefix = goalPrefix,
                description = description,
                url = url,
                mojos = mojos
            )
        } catch (e: Exception) {
            log.warn("Failed to parse plugin.xml: ${e.message}")
            null
        }
    }

    private fun parseMojo(element: Element): MojoDescriptor? {
        val goal = getChildText(element, "goal") ?: return null
        val description = getChildText(element, "description")
        val defaultPhase = getChildText(element, "phase")

        val parameters = mutableListOf<MojoParameter>()
        val parametersElement = getChildElement(element, "parameters")
        if (parametersElement != null) {
            val paramElements = parametersElement.getElementsByTagName("parameter")
            for (i in 0 until paramElements.length) {
                val paramElement = paramElements.item(i) as? Element ?: continue
                if (paramElement.parentNode != parametersElement) continue
                parseParameter(paramElement)?.let { parameters.add(it) }
            }
        }

        return MojoDescriptor(
            goal = goal,
            description = description,
            defaultPhase = defaultPhase,
            parameters = parameters
        )
    }

    private fun parseParameter(element: Element): MojoParameter? {
        val name = getChildText(element, "name") ?: return null
        val type = getChildText(element, "type") ?: "java.lang.String"
        val required = getChildText(element, "required")?.toBoolean() ?: false
        val defaultValue = getChildText(element, "defaultValue")
        val description = getChildText(element, "description")
        val expression = getChildText(element, "expression")
        val editable = getChildText(element, "editable")?.toBoolean() ?: true

        return MojoParameter(
            name = name,
            type = type,
            required = required,
            defaultValue = defaultValue,
            description = description,
            expression = expression,
            editable = editable
        )
    }

    private fun getChildText(element: Element, tagName: String): String? {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                return child.textContent?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun getChildElement(element: Element, tagName: String): Element? {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                return child as Element
            }
        }
        return null
    }

    fun clearCache() {
        cache.clear()
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "size" to cache.size,
            "entries" to cache.keys.toList().take(20)
        )
    }
}
