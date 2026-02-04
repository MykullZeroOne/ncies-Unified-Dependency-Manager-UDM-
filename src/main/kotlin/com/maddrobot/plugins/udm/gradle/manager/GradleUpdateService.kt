package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.diagnostic.Logger
import com.maddrobot.plugins.udm.maven.DependencyService
import com.maddrobot.plugins.udm.util.HttpRequestHelper
import com.maddrobot.plugins.udm.maven.MavenSearchResult
import kotlinx.serialization.json.Json

class GradleUpdateService {
    private val log = Logger.getInstance(javaClass)

    fun getLatestVersion(groupId: String, artifactId: String): String? {
        val query = "$groupId:$artifactId"
        val url = DependencyService.mavenSearchUrl(query, rowsLimit = 1)
        
        return when (val result = HttpRequestHelper.getForList(url) { response -> parseResponse(response) }) {
            is HttpRequestHelper.RequestResult.Success -> {
                result.data.firstOrNull()?.version
            }
            is HttpRequestHelper.RequestResult.Error -> {
                log.error("Failed to fetch latest version for $groupId:$artifactId", result.exception)
                null
            }
        }
    }

    private fun parseResponse(responseBody: String): List<DependencyVersionInfo> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val artifactResponse = json.decodeFromString(MavenSearchResult.serializer(), responseBody)
            artifactResponse.response.centralDependencies.map { 
                DependencyVersionInfo(it.groupId, it.artifactId, it.version)
            }
        } catch (e: Exception) {
            log.error("Failed to parse response: ${e.localizedMessage}", e)
            emptyList()
        }
    }

    data class DependencyVersionInfo(val groupId: String, val artifactId: String, val version: String)
}
