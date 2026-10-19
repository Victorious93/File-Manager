package ai.victorious.victorfiles.core.cloud

import ai.victorious.victorfiles.core.net.RemoteProtocol
import ai.victorious.victorfiles.core.net.SavedConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class S3BackendTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun backend(): S3RemoteBackend {
        val endpoint = "http://" + server.hostName + ":" + server.port
        val conn = SavedConnection(
            id = "s3a", name = "minio", protocol = RemoteProtocol.S3, host = "", port = 443,
            username = "AKIAEXAMPLE", region = "us-east-1", bucket = "mybucket",
            endpoint = endpoint, s3PathStyle = true,
        )
        return S3RemoteBackend(conn, SigV4.Credentials("AKIAEXAMPLE", "secret"))
    }

    @Test fun listSignsAndParses() {
        server.enqueue(
            MockResponse().setBody(
                """<?xml version="1.0"?><ListBucketResult>
                   <IsTruncated>false</IsTruncated>
                   <Contents><Key>dir/a.txt</Key><Size>5</Size><LastModified>2026-01-02T03:04:05.000Z</LastModified></Contents>
                   <CommonPrefixes><Prefix>dir/sub/</Prefix></CommonPrefixes>
                   </ListBucketResult>""",
            ),
        )
        val entries = backend().list("/dir")
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "sub" && it.isDirectory })
        assertTrue(entries.any { it.name == "a.txt" && !it.isDirectory && it.sizeBytes == 5L })

        val req = server.takeRequest()
        // Path-style bucket in the URI, SigV4 Authorization header present and scoped to s3.
        assertTrue(req.path!!.startsWith("/mybucket"))
        assertTrue(req.path!!.contains("list-type=2"))
        val auth = req.getHeader("Authorization")!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/"))
        assertTrue(auth.contains("/us-east-1/s3/aws4_request"))
        assertNotNull(req.getHeader("x-amz-content-sha256"))
        assertNotNull(req.getHeader("x-amz-date"))
    }
}
