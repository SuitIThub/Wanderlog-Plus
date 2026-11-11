package de.wanderlogplus.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class YenEuroTest {
    private val rate = 0.006 // 1 Yen = 0,006 €

    @Test fun singleAmountsInEveryNotation() {
        assertEquals("Eintritt ¥1,200 (≈ 7 €)", withEuro("Eintritt ¥1,200", rate))
        assertEquals("Eintritt ￥ 500 (≈ 3 €)", withEuro("Eintritt ￥ 500", rate))
        assertEquals("Menü 3.000 Yen (≈ 18 €) pro Person", withEuro("Menü 3.000 Yen pro Person", rate))
        assertEquals("1500円 (≈ 9 €)", withEuro("1500円", rate))
        assertEquals("JPY 10000 (≈ 60 €)", withEuro("JPY 10000", rate))
        assertEquals("2万円 (≈ 120 €)", withEuro("2万円", rate))
        assertEquals("1,5万円 (≈ 90 €)", withEuro("1,5万円", rate))
        assertEquals("Snack ¥150 (≈ 0,9 €)", withEuro("Snack ¥150", rate))
    }

    @Test fun rangesGetOneEuroRange() {
        assertEquals("Abends ￥1,000～￥1,999 (≈ 6–12 €)", withEuro("Abends ￥1,000～￥1,999", rate))
        assertEquals("1000-2000円 (≈ 6–12 €)", withEuro("1000-2000円", rate))
        assertEquals("¥3,000 – 5,000 (≈ 18–30 €)", withEuro("¥3,000 – 5,000", rate))
    }

    @Test fun leavesOtherTextAlone() {
        assertEquals("11:00 - 20:00, Gleis 3", withEuro("11:00 - 20:00, Gleis 3", rate))
        assertEquals("Yenom ist ein Name 300", withEuro("Yenom ist ein Name 300", rate))
        assertEquals("¥1,200 (≈ 7 €)", withEuro("¥1,200 (≈ 7 €)", rate)) // never twice
        assertEquals("¥1,200", withEuro("¥1,200", null)) // no rate yet
        assertEquals("", withEuro("", rate))
    }

    @Test fun largeAmountsUseThousandsDots() {
        assertEquals("Ryokan ¥250,000 (≈ 1.500 €)", withEuro("Ryokan ¥250,000", rate))
    }
}
