package com.ebbinghaus.review.data.sync.remote

import com.ebbinghaus.review.data.security.CredentialStore
import com.ebbinghaus.review.data.security.SecureLogger
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GiteeTransportContractTest {
    @Test
    fun repositoryParsingAcceptsEmptyDefaultBranchAndSingularPermission() = runBlocking {
        val transport = transport { request ->
            assertEquals(TEST_TOKEN, request.url.queryParameter("access_token"))
            assertNull(request.header("Authorization"))
            response(
                request,
                200,
                """
                    {
                      "path": "ebbinghaus-notes",
                      "private": true,
                      "default_branch": null,
                      "namespace": {"path": "raink3"},
                      "permission": {"pull": true, "push": true, "admin": true}
                    }
                """.trimIndent()
            )
        }

        val repository = transport.getRepository("raink3", "ebbinghaus-notes", CREDENTIAL_ALIAS)

        assertEquals("raink3", repository.owner)
        assertEquals("ebbinghaus-notes", repository.name)
        assertNull(repository.defaultBranch)
        assertTrue(repository.privateRepository)
        assertTrue(repository.canPush)
    }

    @Test
    fun emptySuccessfulContentResponseIsNormalizedToNotFound() = runBlocking {
        val transport = transport { request -> response(request, 200, "[]") }

        try {
            transport.getContent("raink3", "ebbinghaus-notes", "missing.md", "master", CREDENTIAL_ALIAS)
            fail("Expected a normalized 404")
        } catch (error: GiteeApiException) {
            assertEquals(404, error.statusCode)
            assertEquals("Content not found", error.responseBody)
        }
    }

    @Test
    fun createCommitUsesDocumentedPathAndBodyCredential() = runBlocking {
        val transport = transport { request ->
            assertNull(request.url.queryParameter("access_token"))
            assertNull(request.header("Authorization"))
            val payload = JsonParser.parseString(request.bodyText()).asJsonObject
            assertEquals(TEST_TOKEN, payload.get("access_token").asString)
            val action = payload.getAsJsonArray("actions")[0].asJsonObject
            assertEquals("notes/test.md", action.get("path").asString)
            assertFalse(action.has("file_path"))
            response(request, 201, "{\"sha\":\"created-sha\"}")
        }

        val created = transport.createCommit(
            "raink3",
            "ebbinghaus-notes",
            "master",
            "test commit",
            listOf(GiteeCommitAction("notes/test.md", "body\n".toByteArray(), binary = false)),
            CREDENTIAL_ALIAS
        )

        assertEquals("created-sha", created.sha)
    }

    @Test
    fun createBranchUsesRefsAndBranchName() = runBlocking {
        val transport = transport { request ->
            val payload = JsonParser.parseString(request.bodyText()).asJsonObject
            assertEquals(TEST_TOKEN, payload.get("access_token").asString)
            assertEquals("main", payload.get("refs").asString)
            assertEquals("master", payload.get("branch_name").asString)
            response(
                request,
                201,
                "{\"name\":\"master\",\"commit\":{\"sha\":\"branch-sha\"}}"
            )
        }

        val branch = transport.createBranch(
            "raink3",
            "ebbinghaus-notes",
            "main",
            "master",
            CREDENTIAL_ALIAS
        )

        assertEquals(GiteeBranch("master", "branch-sha"), branch)
    }

    private fun transport(handler: (okhttp3.Request) -> Response): OkHttpGiteeTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()
        return OkHttpGiteeTransport(
            credentialStore = InMemoryCredentialStore,
            callRegistry = CredentialCallRegistry(),
            client = client,
            logger = NoOpLogger
        )
    }

    private fun response(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("contract")
            .body(body.toResponseBody(JSON_MEDIA_TYPE))
            .build()

    private fun okhttp3.Request.bodyText(): String = Buffer().also { buffer ->
        requireNotNull(body).writeTo(buffer)
    }.readUtf8()

    private object InMemoryCredentialStore : CredentialStore {
        override fun contains(credentialAlias: String) = credentialAlias == CREDENTIAL_ALIAS
        override fun save(credentialAlias: String, personalAccessToken: String) = Unit
        override fun remove(credentialAlias: String) = Unit
        override fun <T> withCredential(credentialAlias: String, block: (CharArray) -> T): T? =
            if (credentialAlias == CREDENTIAL_ALIAS) block(TEST_TOKEN.toCharArray()) else null
    }

    private object NoOpLogger : SecureLogger {
        override fun debug(tag: String, message: String, sensitiveValues: Iterable<String>) = Unit
        override fun warning(tag: String, message: String, sensitiveValues: Iterable<String>) = Unit
        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
            sensitiveValues: Iterable<String>
        ) = Unit
    }

    private companion object {
        const val CREDENTIAL_ALIAS = "contract-credential"
        const val TEST_TOKEN = "contract-token"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
