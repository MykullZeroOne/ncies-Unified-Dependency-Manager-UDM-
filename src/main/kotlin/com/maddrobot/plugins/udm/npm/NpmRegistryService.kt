package com.maddrobot.plugins.udm.npm

import kotlinx.serialization.json.Json
import com.maddrobot.plugins.udm.util.HttpRequestHelper
import com.maddrobot.plugins.udm.util.showErrorDialog

/**
 * Provides services for interacting with the NPM Registry.
 *
 * This class is responsible for performing search operations against the NPM Registry API. It
 * constructs the appropriate request URLs, handles HTTP requests, parses responses, and processes
 * the data obtained from the API.
 *
 * Functionality includes:
 * - Searching the NPM Registry based on keywords.
 * - Parsing JSON responses into model objects such as `NpmRegistrySearchResult`.
 * - Handling errors and displaying error messages to the user.
 *
 * The main purpose of this class is to facilitate seamless interaction with the NPM Registry
 * without requiring the consumer to handle HTTP requests or JSON parsing directly.
 */
object NpmRegistryService {

    private const val NPM_PACKAGE_SEARCH_ENDPOINT = "https://registry.npmjs.com/-/v1/search"

    fun search(keyword: String): List<NpmObject> {
        val url = npmPackageSearchUrl(keyword)

        return when (val result = HttpRequestHelper.getForObject(url, ::parseResponse)) {
            is HttpRequestHelper.RequestResult.Success -> {
                result.data?.objects ?: emptyList()
            }

            is HttpRequestHelper.RequestResult.Error -> {
                showErrorDialog(result.exception.localizedMessage)
                emptyList()
            }
        }
    }

    private fun parseResponse(responseBody: String): NpmRegistrySearchResult {
        val json = Json { ignoreUnknownKeys = true } // Ignore unknown properties during deserialization
        return json.decodeFromString(NpmRegistrySearchResult.serializer(), responseBody)
    }

    private fun npmPackageSearchUrl(text: String): String {
        return "$NPM_PACKAGE_SEARCH_ENDPOINT?text=$text"
    }
}
