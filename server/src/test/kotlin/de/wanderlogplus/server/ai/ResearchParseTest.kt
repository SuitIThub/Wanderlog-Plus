package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.ReservationAdvice
import de.wanderlogplus.shared.model.ReservationNeed
import de.wanderlogplus.shared.sync.AssessConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResearchParseTest {
    private fun parse(extra: String) = parseResearch(Json.parseToJsonElement("""{"description":"x"$extra}""").jsonObject, AssessConfig(), emptyList())

    @Test
    fun `reservation advice is parsed`() {
        val r = parse(""","reservation":{"need":"REQUIRED","opensDaysBefore":30,"opensOn":null,"byDaysBefore":3,"note":"Online ab 30 Tage vorher."}""")
        assertEquals(ReservationAdvice(ReservationNeed.REQUIRED, 30, null, 3, "Online ab 30 Tage vorher."), r.dossier.reservation)
        assertEquals(ReservationNeed.UNKNOWN, parse(""","reservation":{"need":"maybe"}""").dossier.reservation?.need)
        assertEquals("2027-04-10", parse(""","reservation":{"need":"REQUIRED","opensOn":"2027-04-10"}""").dossier.reservation?.opensOn)
        assertNull(parse(""","reservation":{"need":"REQUIRED","opensOn":"bald"}""").dossier.reservation?.opensOn)
        assertNull(parse("").dossier.reservation)
    }
}
