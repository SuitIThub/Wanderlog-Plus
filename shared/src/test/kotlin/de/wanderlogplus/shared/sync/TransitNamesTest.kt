package de.wanderlogplus.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class TransitNamesTest {
    @Test fun readableLines() {
        assertEquals("JR Shinkansen Nozomi 15", TransitNames.line("ＪＲ新幹線のぞみ15号"))
        assertEquals("JR Ltd. Exp. Shinano 7", TransitNames.line("ＪＲ特急しなの7号"))
        assertEquals("JR Chūō-Hauptlinie (Section Rapid)", TransitNames.line("ＪＲ中央本線区間快速"))
        assertEquals("Keihan Hauptlinie (Semi Exp.)", TransitNames.line("京阪本線準急"))
        assertEquals("JR Shinkansen Kagayaki 505", TransitNames.line("ＪＲ新幹線かがやき505号"))
        assertEquals("zu Fuß", TransitNames.line("徒歩"))
        assertEquals("Ltd. Exp. Wide View Hida 3", TransitNames.line("特急ワイドビューひだ3号"))
        // Seen in a real answer (Kyoto, May 2027).
        assertEquals("Kyōto U-Bahn Tōzai-Linie", TransitNames.line("京都市営東西線"))
        assertEquals("JR Nara-Linie", TransitNames.line("ＪＲ奈良線"))
        // Hiroshima's tram.
        assertEquals("Hiroden Miyajima-Linie", TransitNames.line("広島電鉄宮島線"))
        assertEquals("Hiroden (Straßenbahn)", TransitNames.line("広島電鉄路面電車"))
        // Seen in a real answer (Hiroshima, May 2027): numbered tram lines.
        assertEquals("Hiroden Linie 6", TransitNames.line("広島電鉄６号線"))
    }

    @Test fun headsignsAndFees() {
        assertEquals("Richtung 博多", TransitNames.headsign("博多行"))
        assertEquals("Richtung Hakata", TransitNames.headsign("博多行", "Hakata"))
        assertEquals("", TransitNames.headsign(""))
        assertEquals("Zuschlag, reservierter Sitz", TransitNames.fee("指定席"))
        assertEquals("寝台", TransitNames.fee("寝台"))
        assertEquals("JR 15", TransitNames.ascii("ＪＲ　１５"))
    }
}
