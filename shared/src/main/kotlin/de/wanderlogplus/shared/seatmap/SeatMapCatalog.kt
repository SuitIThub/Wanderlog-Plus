package de.wanderlogplus.shared.seatmap

import de.wanderlogplus.shared.seatmap.Cell.Aisle
import de.wanderlogplus.shared.seatmap.Cell.FacilityCell
import de.wanderlogplus.shared.seatmap.Cell.Gap
import de.wanderlogplus.shared.seatmap.Cell.SeatCell

/**
 * The seat maps, transcribed from the plans the travellers supplied (airline and DB seating charts). New models are
 * new entries here; the app draws every one of them.
 */
internal object SeatMapCatalog {
    /**
     * An aircraft row from a pattern across the cabin, left (A) to right: a letter is a seat, '|' the aisle, '.' an
     * empty spot. The outermost places are the windows.
     */
    private fun row(number: Int, pattern: String): SeatRow {
        val cells = pattern.map { c ->
            when (c) {
                '|' -> Aisle
                '.' -> Gap
                else -> SeatCell("$number$c")
            }
        }
        return SeatRow(number.toString(), cells.mapIndexed { i, c -> if (c is SeatCell && (i == 0 || i == cells.lastIndex)) c.copy(window = true) else c })
    }

    private fun rows(range: Iterable<Int>, pattern: String, except: Map<Int, String> = emptyMap(), skip: Set<Int> = emptySet()) =
        range.filter { it !in skip }.map { row(it, except[it] ?: pattern) }

    private fun facilities(width: Int, vararg f: Facility): SeatRow {
        val cells = MutableList<Cell>(width) { Gap }
        f.forEachIndexed { i, x -> if (i < width) cells[if (i == 0) 0 else width - 1] = FacilityCell(x) }
        return SeatRow("", cells)
    }

    /** A position along a train car: the two seats on the double side (window first), the aisle, the single seat. */
    private fun car(label: Int, window: String?, aisle: String?, single: String?): SeatRow = SeatRow(
        label.toString(),
        listOf(
            window?.let { SeatCell(it, window = true) } ?: Gap,
            aisle?.let { SeatCell(it) } ?: Gap,
            Aisle,
            single?.let { SeatCell(it, window = true) } ?: Gap,
        ),
    )

    private val ice4 = SeatMapModel(
        "db-ice4-2kl", "ICE 4 (BR 412), Wagen 2. Klasse", SeatMapKind.TRAIN_CAR, listOf("ICE 571", "ICE 1272"),
        listOf(
            SeatSection(
                "2. Klasse",
                listOf(
                    SeatRow("", listOf(FacilityCell(Facility.TOILET), Gap, Aisle, Gap)),
                    car(1, null, null, "11"), car(2, "16", "15", "14"), car(3, "23", "22", "21"), car(4, "33", "32", "31"),
                    car(5, "36", "35", "34"), car(6, "43", "42", null), car(7, "46", "45", "44"), car(8, "53", "52", "51"),
                    car(9, "56", "55", "54"), car(10, "63", "62", "61"), car(11, "66", "65", "64"), car(12, "73", "72", "71"),
                    car(13, "76", "75", "74"), car(14, "83", "82", "81"), car(15, "86", "85", "84"), car(16, "93", "92", "91"),
                    car(17, "96", "95", "94"), car(18, "103", "102", "101"), car(19, "106", "105", null), car(20, "113", "112", "111"),
                    car(21, "116", "115", "114"), car(22, "123", "122", "121"), car(23, "126", "125", "124"), car(24, "133", null, "131"),
                    SeatRow("", listOf(FacilityCell(Facility.LUGGAGE), Gap, Aisle, Gap)),
                ),
            ),
        ),
    )

    private val ciA359 = SeatMapModel(
        "ci-a359", "China Airlines A350-900", SeatMapKind.AIRCRAFT, listOf("CI 61", "CI 62", "CI 100"),
        listOf(
            SeatSection("Business", rows(listOf(10, 11, 12, 14, 15, 16, 17, 18), "A|DG|K")),
            SeatSection("Premium Economy", listOf(facilities(7, Facility.EXIT, Facility.EXIT)) + rows(31..34, "AC|DEG|HK")),
            SeatSection(
                "Economy",
                rows(40..52, "ABC|DEG|HJK", skip = setOf(44)) + facilities(11, Facility.EXIT, Facility.EXIT) +
                    rows(60..75, "ABC|DEG|HJK", except = mapOf(75 to "...|DEG|HJK")),
            ),
        ),
    )

    private val ciA21n = SeatMapModel(
        "ci-a21n", "China Airlines A321neo", SeatMapKind.AIRCRAFT, listOf("CI 105"),
        listOf(
            SeatSection("Business", rows(10..12, "AC|HK")),
            SeatSection("Economy", rows(40..68, "ABC|HJK", skip = setOf(44))),
        ),
    )

    private val nhA21n = SeatMapModel(
        "nh-a21n", "ANA A321neo", SeatMapKind.AIRCRAFT, listOf("NH 1861"),
        listOf(
            SeatSection("Premium Class", rows(1..2, "AC|HK")),
            SeatSection(
                "Economy",
                rows(5..10, "ABC|HJK", except = mapOf(10 to ".BC|HJ.")) + facilities(7, Facility.EXIT, Facility.EXIT) +
                    rows(11..23, "ABC|HJK", except = mapOf(11 to "...|HJK", 23 to "ABC|HJ.")) + facilities(7, Facility.EXIT, Facility.EXIT) +
                    rows(24..37, "ABC|HJK", except = mapOf(24 to "...|HJK", 37 to "ABC|...")),
            ),
        ),
    )

    private val nh789 = SeatMapModel(
        "nh-789", "ANA 787-9", SeatMapKind.AIRCRAFT, listOf("NH 468"),
        listOf(
            SeatSection(
                "Business",
                rows(1..8, "A|DF|H", except = mapOf(1 to "A|..|H", 2 to "C|EG|K", 4 to "C|EG|K", 6 to "C|EG|K", 8 to "C|EG|K")) +
                    facilities(6, Facility.EXIT, Facility.EXIT) +
                    rows(9..11, "A|DF|H", except = mapOf(9 to "A|..|H", 10 to "C|EG|K")),
            ),
            SeatSection("Premium Economy", rows(15..16, "AC|DFG|HK")),
            SeatSection(
                "Economy",
                rows(20..27, "ABC|DFG|HJK") + facilities(11, Facility.EXIT, Facility.EXIT) +
                    rows(28..42, "ABC|DFG|HJK", except = mapOf(28 to "ABC|...|HJK", 41 to "...|DFG|...", 42 to "...|DFG|...")),
            ),
        ),
    )

    val models: List<SeatMapModel> = listOf(ice4, ciA359, ciA21n, nhA21n, nh789)
}
