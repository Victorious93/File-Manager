package ai.victorious.victorfiles.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** AWS SigV4 published test-suite vectors (aws-sig-v4-test-suite). */
class SigV4Test {
    // Standard test-suite credentials + scope.
    private val creds = SigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
    private val region = "us-east-1"
    private val service = "service"
    private val amzDate = "20150830T123600Z"
    private val emptyHash = SigV4.sha256Hex("")

    @Test fun getVanilla() {
        val signed = SigV4.sign(
            method = "GET",
            canonicalUri = "/",
            queryParams = emptyMap(),
            headers = mapOf("host" to "example.amazonaws.com", "x-amz-date" to amzDate),
            payloadHash = emptyHash,
            credentials = creds, region = region, service = service, amzDate = amzDate,
        )
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", signed.signature)
        assertEquals("host;x-amz-date", signed.signedHeaders)
    }

    @Test fun getVanillaQuery() {
        val signed = SigV4.sign(
            method = "GET",
            canonicalUri = "/",
            queryParams = mapOf("Param1" to "value1", "Param2" to "value2"),
            headers = mapOf("host" to "example.amazonaws.com", "x-amz-date" to amzDate),
            payloadHash = emptyHash,
            credentials = creds, region = region, service = service, amzDate = amzDate,
        )
        assertEquals("b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500", signed.signature)
    }

    @Test fun signingKeyDerivation() {
        // Known kSigning from the AWS SigV4 docs example (same creds/scope).
        val key = SigV4.deriveSigningKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830", "us-east-1", "iam")
        assertEquals("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9", SigV4.hex(key))
    }

    @Test fun emptyPayloadHashIsCanonical() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
    }

    @Test fun uriEncodeKeepsAndEscapes() {
        assertEquals("a%20b", SigV4.uriEncode("a b", true))
        assertEquals("a/b", SigV4.uriEncode("a/b", false))
        assertEquals("a%2Fb", SigV4.uriEncode("a/b", true))
    }
}
