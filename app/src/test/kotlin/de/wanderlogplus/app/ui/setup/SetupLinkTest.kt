package de.wanderlogplus.app.ui.setup

import de.wanderlogplus.app.data.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLinkTest {
    @Test fun roundTrip() {
        val s = ServerSettings("https://wlp.example.org/sync", "tok&en=1 x")
        assertEquals(s, SetupLink.parse(SetupLink.build(s)))
    }

    @Test fun rejectsOtherScheme() {
        assertNull(SetupLink.parse("https://setup?url=https%3A%2F%2Fa.org&token=t"))
        assertNull(SetupLink.parse("wlp://other?url=https%3A%2F%2Fa.org&token=t"))
    }

    @Test fun rejectsMissingToken() {
        assertNull(SetupLink.parse("wlp://setup?url=https%3A%2F%2Fa.org"))
        assertNull(SetupLink.parse("wlp://setup?url=https%3A%2F%2Fa.org&token="))
    }

    @Test fun rejectsInvalidUrl() {
        assertNull(SetupLink.parse("wlp://setup?url=ht%20tp%3A%2F%2Fx%20y&token=t"))
    }

    @Test fun decodesEncodedToken() {
        val s = SetupLink.parse("wlp://setup?url=https%3A%2F%2Fa.org&token=a%2Bb%2Fc%3D")
        assertEquals("a+b/c=", s?.token)
    }
}
