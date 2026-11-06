package de.wanderlogplus.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerConfigTest {
    private val ok = mapOf("WLP_TOKENS" to "alice:$TOKEN_A,bob:$TOKEN_B")

    @Test
    fun researchParallelism() {
        assertEquals(10, ServerConfig.fromEnv(ok).researchParallelism)
        assertEquals(16, ServerConfig.fromEnv(ok + ("WLP_RESEARCH_PARALLELISM" to "16")).researchParallelism)
        assertEquals(10, ServerConfig.fromEnv(ok + ("WLP_RESEARCH_PARALLELISM" to "0")).researchParallelism)
        assertEquals(10, ServerConfig.fromEnv(ok + ("WLP_RESEARCH_PARALLELISM" to "21")).researchParallelism)
        assertEquals(10, ServerConfig.fromEnv(ok + ("WLP_RESEARCH_PARALLELISM" to "abc")).researchParallelism)
    }

    @Test
    fun defaults() {
        val c = ServerConfig.fromEnv(ok)
        assertEquals(mapOf("alice" to TOKEN_A, "bob" to TOKEN_B), c.tokens)
        assertEquals(Path.of("/data"), c.dataDir)
        assertEquals(8080, c.port)
        assertEquals(25L * 1024 * 1024, c.maxFileBytes)
        assertNull(c.tripKey)
        assertNull(c.importFile)
    }

    @Test
    fun overrides() {
        val c = ServerConfig.fromEnv(
            ok + mapOf(
                "WLP_DATA_DIR" to "/x", "WLP_PORT" to "9000", "WLP_MAX_FILE_MB" to "2",
                "WLP_TRIP_KEY" to "k", "WLP_IMPORT_FILE" to "/f.json",
            )
        )
        assertEquals(Path.of("/x"), c.dataDir)
        assertEquals(9000, c.port)
        assertEquals(2L * 1024 * 1024, c.maxFileBytes)
        assertEquals("k", c.tripKey)
        assertEquals(Path.of("/f.json"), c.importFile)
    }

    private fun bad(env: Map<String, String>) {
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env) }
    }

    @Test
    fun aiModels() {
        val d = ServerConfig.fromEnv(ok)
        assertEquals("google/gemini-2.5-flash", d.aiModel)
        assertEquals("anthropic/claude-sonnet-5-5", d.textModel)
        val c = ServerConfig.fromEnv(ok + mapOf("WLP_AI_MODEL" to "m/media", "WLP_TEXT_MODEL" to "m/text"))
        assertEquals("m/media", c.aiModel)
        assertEquals("m/text", c.textModel)
    }

    @Test fun missingTokens() = bad(emptyMap())
    @Test fun shortToken() = bad(mapOf("WLP_TOKENS" to "alice:short"))
    @Test fun duplicateName() = bad(mapOf("WLP_TOKENS" to "alice:$TOKEN_A,alice:$TOKEN_B"))
    @Test fun duplicateToken() = bad(mapOf("WLP_TOKENS" to "alice:$TOKEN_A,bob:$TOKEN_A"))
    @Test fun entryWithoutColon() = bad(mapOf("WLP_TOKENS" to "alice$TOKEN_A"))
    @Test fun badPort() = bad(ok + ("WLP_PORT" to "abc"))
}
