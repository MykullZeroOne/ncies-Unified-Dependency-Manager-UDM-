package com.maddrobot.plugins.udm.maven

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import com.maddrobot.plugins.udm.setting.PackageFinderSetting
import com.maddrobot.plugins.udm.util.HttpRequestHelper
import com.maddrobot.plugins.udm.util.showDialogWithConfigButton
import com.maddrobot.plugins.udm.util.showInformationNotification
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * refer: https://central.sonatype.org/search/rest-api-guide/
 *
 * madd robot tech
 * @LastModified: 2025-07-16
 * @since 2025-01-19
 */
object DependencyService {

    private val log = Logger.getInstance(javaClass)

    fun searchFromMavenCentral(text: String): List<Dependency> {
        val url = mavenSearchUrl(text, rowsLimit = 200)
        val result = HttpRequestHelper.getForList(url) { response -> parseCentralResponse(response) }
        return when (result) {
            is HttpRequestHelper.RequestResult.Success -> result.data
            is HttpRequestHelper.RequestResult.Error -> {
                log.warn("Maven Central search failed: ${result.exception.message}")
                showInformationNotification("Maven Central search failed: ${result.exception.localizedMessage}")
                emptyList()
            }
        }
    }

    fun searchFromNexus(text: String): List<Dependency> {
        return nexusSearchUrl(text)?.let { url ->
            when (val result = HttpRequestHelper.getForList(url, ::parseNexusResponse)) {
                is HttpRequestHelper.RequestResult.Success -> result.data
                is HttpRequestHelper.RequestResult.Error -> {
                    showDialogWithConfigButton(result.exception.localizedMessage)
                    emptyList()
                }
            }
        } ?: emptyList()
    }

    private fun parseCentralResponse(responseBody: String): List<Dependency> {
        return try {
            val json = Json { ignoreUnknownKeys = true } // Ignore unknown properties during deserialization
            val artifactResponse = json.decodeFromString(MavenSearchResult.serializer(), responseBody)
            artifactResponse.response.centralDependencies
        } catch (e: Exception) {
            log.error("Failed to parse dependency info from Maven Central: ${e.localizedMessage}", e)
            emptyList()
        }
    }

    private fun parseNexusResponse(responseBody: String): List<NexusDependency> {
        return try {
            val json = Json { ignoreUnknownKeys = true } // Ignore unknown properties during deserialization
            val searchResult = json.decodeFromString(NexusSearchResult.serializer(), responseBody)
            val result = mutableListOf<NexusDependency>()

            for (item in searchResult.items) {
                if (item.assets.isEmpty()) continue

                val firstAsset = item.assets.first()
                val maven2Info = firstAsset.maven2
                val uploaderIp = firstAsset.uploaderIp

                val downloadInfos = item.assets.map { asset ->
                    DownloadInfo(
                        extension = asset.maven2.extension,
                        downloadUrl = asset.downloadUrl
                    )
                }

                result.add(
                    NexusDependency(
                        groupId = maven2Info.groupId,
                        artifactId = maven2Info.artifactId,
                        version = maven2Info.version,
                        uploaderIp = uploaderIp,
                        downloadInfos = downloadInfos
                    )
                )
            }

            result
        } catch (e: Exception) {
            log.error("Failed to parse dependency info from Nexus: ${e.localizedMessage}", e)
            emptyList()
        }
    }

    /**
     * Build the Maven Central repository search URL.
     *
     * @param query Search keyword. Supported formats:
     * - `g:group`: search by group (e.g., `g:com.example`)
     * - `a:artifact`: search by artifact (e.g., `a:lombok`)
     * - `group:artifact`: search by group and artifact (e.g., `com.example:lombok`)
     * - `artifact`: direct artifact search (e.g., `lombok`)
     * @param version Optional version (e.g., `1.0.0`)
     * @param packaging Optional packaging type (e.g., `jar`)
     * @param rowsLimit Optional max results, default 20
     * @return The constructed Maven Central search URL
     *
     * Examples:
     * ```
     * mavenSearchUrl("g:com.example") // search by group
     * mavenSearchUrl("a:lombok") // search by artifact
     * mavenSearchUrl("lombok") // search by artifact
     * mavenSearchUrl("com.example:lombok") // search by group and artifact
     * ```
     */
    fun mavenSearchUrl(
        query: String, // Supports formats: g:group, a:artifact, artifact, or group:artifact
        version: String? = null, // Optional: version
        packaging: String? = null, // Optional: packaging type
        rowsLimit: Int = 20 // Optional: maximum number of results
    ): String {
        val (group, artifactId) = when {
            query.startsWith("g:") -> Pair(query.removePrefix("g:"), null)
            query.startsWith("a:") -> Pair(null, query.removePrefix("a:"))
            query.contains(":") -> {
                val parts = query.split(":")
                Pair(parts[0], parts[1])
            }

            else -> Pair(null, query)
        }

        // Build the query expression
        val q = listOf(
            "g" to group,
            "a" to artifactId,
            "v" to version,
            "p" to packaging
        )
            // Filter out key-value pairs with null values
            .filter { it.second != null }
            .joinToString(separator = " AND ") { "${it.first}:\"${it.second}\"" }

        return "https://search.maven.org/solrsearch/select?q=${q.encodeURL()}&core=gav&rows=$rowsLimit&wt=json"
    }

    fun mavenDownloadUrl(
        group: String,
        artifactId: String,
        version: String,
        ec: String
    ): String {
        val groupPath = group.replace(".", "/")
        return "https://search.maven.org/remotecontent?filepath=$groupPath/$artifactId/$version/$artifactId-$version$ec"
    }

    private fun nexusSearchUrl(q: String): String? {
        val setting = PackageFinderSetting.instance
        if (setting.nexusServerUrl.isBlank()) {
            showDialogWithConfigButton()
            return null
        }

        val nexusServerUrl = setting.nexusServerUrl.trimEnd('/')
        return "$nexusServerUrl/service/rest/v1/search?sort=version&direction=desc&q=$q"
    }

    private fun String.encodeURL(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
}
