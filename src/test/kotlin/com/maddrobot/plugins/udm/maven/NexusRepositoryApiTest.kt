package com.maddrobot.plugins.udm.maven

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * madd robot tech
 * @LastModified: 2025-07-07
 * @since 2025-07-07
 */
class NexusRepositoryApiTest {
    @Test
    fun `test search`() {
        val searchKeyword = "org.junit"
        // Test cases where trailing slashes are included
        val host = "http://localhost:8081/"
        val url = "${host.trimEnd('/')}/service/rest/v1/search?q=$searchKeyword"
        assertEquals("http://localhost:8081/service/rest/v1/search?q=org.junit", url)
    }
}
