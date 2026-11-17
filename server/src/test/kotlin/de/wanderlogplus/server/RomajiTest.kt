package de.wanderlogplus.server

import kotlin.test.Test
import kotlin.test.assertEquals

class RomajiTest {
    @Test fun stationNamesWithLongVowels() {
        assertEquals("Kyōto", Romaji.of("京都"))
        assertEquals("Tōkyō", Romaji.of("東京"))
        assertEquals("Nagoya", Romaji.of("名古屋"))
        assertEquals("Nakatsugawa", Romaji.of("中津川"))
    }

    @Test fun partsAreJoinedWithHyphens() {
        assertEquals("Shin-Ōsaka", Romaji.of("新大阪"))
    }

    @Test fun genericWordsAreOwnParts() {
        assertEquals("Kyōto-Ekimae", Romaji.of("京都駅前"))
        assertEquals("Narita-Kūkō", Romaji.of("成田空港"))
        assertEquals("Ginkakuji-mae", Romaji.of("銀閣寺前"))
        assertEquals("Nakatsugawa-Eki", Romaji.of("中津川駅"))
        assertEquals("Sanjō-Keihan", Romaji.of("三条京阪"))
        assertEquals("Kiyomizu-Gojō", Romaji.of("清水五条"))
    }

    @Test fun shortEndingsInsideWordsStay() {
        assertEquals("Hokkaidō", Romaji.of("北海道"))
    }

    @Test fun specialReadingsComeFromTheList() {
        assertEquals("Nagiso", Romaji.of("南木曽"))
        assertEquals("Tsumago", Romaji.of("妻籠"))
        assertEquals("Magome", Romaji.of("馬籠"))
    }

    @Test fun kanaToHepburn() {
        assertEquals("Shinjuku", Romaji.kana("シンジュク"))
        assertEquals("Hatchōbori", Romaji.kana("ハッチョウボリ"))
        assertEquals("Kyōto", Romaji.kana("キョウト"))
        assertEquals("Shin'yokohama", Romaji.kana("シンヨコハマ"))
        assertEquals("Tāminaru", Romaji.kana("ターミナル"))
    }

    @Test fun nothingIsGuessed() {
        assertEquals("", Romaji.of(""))
        assertEquals("", Romaji.of("Kyoto"))
    }
}
