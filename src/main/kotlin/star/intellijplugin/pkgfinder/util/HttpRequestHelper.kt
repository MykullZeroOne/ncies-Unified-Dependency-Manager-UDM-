package star.intellijplugin.pkgfinder.util

import com.intellij.util.net.HttpConnectionUtils
import java.net.HttpURLConnection
import java.util.Base64

/**
 * @author drawsta
 * @LastModified: 2026-01-30
 * @since 2025-07-12
 */
object HttpRequestHelper {

    private const val CONNECT_TIMEOUT: Int = 10_000

    private const val READ_TIMEOUT: Int = 10_000

    sealed class RequestResult<out T> {
        data class Success<out T>(val data: T) : RequestResult<T>()
        data class Error(val exception: Throwable, val responseCode: Int? = null) : RequestResult<Nothing>()
    }

    /**
     * Authentication credentials for HTTP requests.
     */
    data class AuthCredentials(
        val username: String,
        val password: String,
        val type: AuthType = AuthType.BASIC
    )

    enum class AuthType {
        BASIC,      // Basic auth (username:password)
        BEARER      // Bearer token (password is the token)
    }

    private fun createConnection(url: String, auth: AuthCredentials? = null, headers: Map<String, String>? = null): HttpURLConnection {
        return HttpConnectionUtils.openHttpConnection(url).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT

            // Add authentication header if provided
            if (auth != null) {
                when (auth.type) {
                    AuthType.BASIC -> {
                        val credentials = "${auth.username}:${auth.password}"
                        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
                        setRequestProperty("Authorization", "Basic $encoded")
                    }
                    AuthType.BEARER -> {
                        setRequestProperty("Authorization", "Bearer ${auth.password}")
                    }
                }
            }

            // Add custom headers
            headers?.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }
    }

    private fun <T> executeRequest(
        url: String,
        auth: AuthCredentials? = null,
        headers: Map<String, String>? = null,
        handleResponse: (HttpURLConnection) -> T
    ): RequestResult<T> {
        return try {
            val connection = createConnection(url, auth, headers)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                // Try to read error response for debugging
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { "" }

                RequestResult.Error(
                    Exception("HTTP error: ${connection.responseCode} - $errorBody"),
                    connection.responseCode
                )
            } else {
                RequestResult.Success(handleResponse(connection))
            }
        } catch (e: Exception) {
            RequestResult.Error(e)
        }
    }

    fun <R> getForObject(
        url: String,
        parser: (String) -> R
    ): RequestResult<R?> {
        return getForObject(url, null, null, parser)
    }

    fun <R> getForObject(
        url: String,
        auth: AuthCredentials?,
        headers: Map<String, String>? = null,
        parser: (String) -> R
    ): RequestResult<R?> {
        return executeRequest(url, auth, headers) { connection ->
            connection.inputStream.bufferedReader().use { reader ->
                val response = reader.readText()
                response.takeUnless { it.isBlank() }?.let(parser)
            }
        }
    }

    fun <T> getForList(
        url: String,
        parser: (String) -> List<T>
    ): RequestResult<List<T>> {
        return getForList(url, null, null, parser)
    }

    fun <T> getForList(
        url: String,
        auth: AuthCredentials?,
        headers: Map<String, String>? = null,
        parser: (String) -> List<T>
    ): RequestResult<List<T>> {
        return executeRequest(url, auth, headers) { connection ->
            connection.inputStream.bufferedReader().use { reader ->
                val response = reader.readText()
                if (response.isBlank()) emptyList() else parser(response)
            }
        }
    }
}
