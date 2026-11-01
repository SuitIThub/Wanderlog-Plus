package de.wanderlogplus.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlTest {
    @Test fun addsHttpsAndTrims() {
        assertEquals("https://sync.example.org", ServerUrl.normalize("sync.example.org").getOrThrow())
        assertEquals("https://sync.example.org", ServerUrl.normalize("https://sync.example.org/").getOrThrow())
        assertEquals("http://192.168.1.10:8080", ServerUrl.normalize("  http://192.168.1.10:8080 ").getOrThrow())
    }

    @Test fun rejectsInvalid() {
        for (s in listOf("ftp://x", "", "http://", "https://", "  ")) {
            val r = ServerUrl.normalize(s)
            assertTrue(r.isFailure, "should fail: '$s'")
            assertEquals("Ungültige Server-Adresse", r.exceptionOrNull()?.message)
        }
    }

    @Test fun insecureDetection() {
        assertFalse(ServerUrl.isInsecure("http://192.168.1.10:8080"))
        assertTrue(ServerUrl.isInsecure("http://sync.example.org"))
        assertFalse(ServerUrl.isInsecure("https://sync.example.org"))
        assertFalse(ServerUrl.isInsecure("http://nas.local"))
        assertFalse(ServerUrl.isInsecure("http://172.20.0.5"))
        assertTrue(ServerUrl.isInsecure("http://172.32.0.5"))
        assertFalse(ServerUrl.isInsecure("http://localhost:8080"))
        assertFalse(ServerUrl.isInsecure("http://127.0.0.1"))
        assertFalse(ServerUrl.isInsecure("http://10.1.2.3"))
        assertTrue(ServerUrl.isInsecure("http://11.1.2.3"))
    }
}

class ServerUrlPrefixTest {
    @Test fun acceptsPathPrefix() {
        assertEquals("https://h.example/wlp", ServerUrl.normalize("https://h.example/wlp/").getOrThrow())
    }

    @Test fun rejectsQueryEvenWithPrefix() {
        assertTrue(ServerUrl.normalize("https://h/wlp?x=1").isFailure)
        assertTrue(ServerUrl.normalize("https://h/wlp#f").isFailure)
        assertTrue(ServerUrl.normalize("https://u@h/wlp").isFailure)
    }
}
