package com.maddrobot.plugins.udm.maven

import org.junit.Test
import com.maddrobot.plugins.udm.maven.DependencyService.mavenDownloadUrl
import com.maddrobot.plugins.udm.maven.DependencyService.mavenSearchUrl

/**
 * madd robot tech
 * @LastModified: 2025-01-23
 * @since 2025-01-23
 */
class DependencyServiceTest {

    @Test
    fun `test mavenSearchUrl`() {
        // Query by group
        assert(
            mavenSearchUrl(
                "g:org.springframework"
            ) == "https://search.maven.org/solrsearch/select?q=g%3A%22org.springframework%22&core=gav&rows=20&wt=json"
        )
        // Query by artifact
        assert(
            mavenSearchUrl(
                "a:spring-core"
            ) == "https://search.maven.org/solrsearch/select?q=a%3A%22spring-core%22&core=gav&rows=20&wt=json"
        )
        // Query by group and artifact
        assert(
            mavenSearchUrl(
                "org.springframework:spring-core"
            ) == "https://search.maven.org/solrsearch/select?q=g%3A%22org.springframework%22+AND+a%3A%22spring-core%22&core=gav&rows=20&wt=json"
        )
        // Pass other optional parameters
        assert(
            mavenSearchUrl(
                "org.springframework:spring-core",
                version = "5.3.9",
                packaging = "jar",
                rowsLimit = 10
            ) == "https://search.maven.org/solrsearch/select?q=g%3A%22org.springframework%22+AND+a%3A%22spring-core%22+AND+v%3A%225.3.9%22+AND+p%3A%22jar%22&core=gav&rows=10&wt=json"
        )
    }

    @Test
    fun `test mavenDownloadUrl`() {
        // Build URL for downloading JAR files
        assert(
            mavenDownloadUrl(
                "org.projectlombok", "lombok", "1.18.36", ".jar"
            ) == "https://search.maven.org/remotecontent?filepath=org/projectlombok/lombok/1.18.36/lombok-1.18.36.jar"
        )

        // Build URL for downloading POM files
        assert(
            mavenDownloadUrl(
                "org.projectlombok", "lombok", "1.18.36", ".pom"
            ) == "https://search.maven.org/remotecontent?filepath=org/projectlombok/lombok/1.18.36/lombok-1.18.36.pom"
        )

        // Build URL for downloading source code JAR files
        assert(
            mavenDownloadUrl(
                "org.projectlombok", "lombok", "1.18.36", "-sources.jar"
            ) == "https://search.maven.org/remotecontent?filepath=org/projectlombok/lombok/1.18.36/lombok-1.18.36-sources.jar"
        )

        // Build URL for downloading javadoc JAR files
        assert(
            mavenDownloadUrl(
                "org.projectlombok", "lombok", "1.18.36", "-javadoc.jar"
            ) == "https://search.maven.org/remotecontent?filepath=org/projectlombok/lombok/1.18.36/lombok-1.18.36-javadoc.jar"
        )
    }
}
