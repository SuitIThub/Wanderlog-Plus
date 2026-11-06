package de.wanderlogplus.shared.seatmap

import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train

enum class SeatMapKind { TRAIN_CAR, AIRCRAFT }

enum class Facility { TOILET, GALLEY, EXIT, LUGGAGE, BABY, WHEELCHAIR, TABLE }

/** One place across a row: a seat, the aisle, an empty spot, or a facility. */
sealed interface Cell {
    data class SeatCell(val code: String, val window: Boolean = false) : Cell
    data object Aisle : Cell
    data object Gap : Cell
    data class FacilityCell(val facility: Facility) : Cell
}

/** A row of an aircraft (front to back) or a position along a train car; [label] is the row number or "". */
data class SeatRow(val label: String, val cells: List<Cell>)

data class SeatSection(val name: String, val rows: List<SeatRow>)

/** A cabin or a car, drawn from data; [matches] are flight or train numbers that use it. */
data class SeatMapModel(val key: String, val name: String, val kind: SeatMapKind, val matches: List<String>, val sections: List<SeatSection>)

enum class SeatPosition { WINDOW, MIDDLE, AISLE }

/** Where a seat is: its section and row, window/middle/aisle, and the seats next to it (same row, same block). */
data class SeatSpot(val section: SeatSection, val row: SeatRow, val position: SeatPosition, val neighbours: List<String>)

object SeatMaps {
    val all: List<SeatMapModel> get() = SeatMapCatalog.models

    fun byKey(key: String?): SeatMapModel? = key?.let { k -> all.firstOrNull { it.key == k } }

    /** "ci 62", "CI0062" and "CI62" are the same: upper case, only letters and digits, no leading zeros of a number. */
    fun normalize(number: String): String =
        number.uppercase().filter { it.isLetterOrDigit() }.replace(Regex("(?<=[A-Z]|^)0+(?=\\d)"), "")

    private fun byNumber(candidates: List<String>): SeatMapModel? {
        val wanted = candidates.filter { it.isNotBlank() }.map(::normalize).toSet()
        return all.firstOrNull { m -> m.matches.any { normalize(it) in wanted } }
    }

    /** The seat map chosen by hand, else the one used for this flight number. */
    fun modelFor(flight: Flight): SeatMapModel? = byKey(flight.seatMap) ?: byNumber(
        listOf(listOfNotNull(flight.airlineCode, flight.number).joinToString(""), flight.number.orEmpty()),
    )

    /** The seat map chosen by hand, else the one used for this train number. */
    fun modelFor(train: Train): SeatMapModel? = byKey(train.seatMap) ?: byNumber(listOf(train.number.orEmpty()))

    /** Where [seat] ("43E", "111") is in [model]; null when the map has no such seat. */
    fun locate(model: SeatMapModel, seat: String): SeatSpot? {
        val code = seat.trim().uppercase()
        if (code.isEmpty()) return null
        model.sections.forEach { section ->
            section.rows.forEach { row ->
                val i = row.cells.indexOfFirst { it is Cell.SeatCell && it.code.uppercase() == code }
                if (i >= 0) {
                    val cell = row.cells[i] as Cell.SeatCell
                    val aisleNext = row.cells.getOrNull(i - 1) == Cell.Aisle || row.cells.getOrNull(i + 1) == Cell.Aisle
                    val position = when {
                        cell.window -> SeatPosition.WINDOW
                        aisleNext -> SeatPosition.AISLE
                        else -> SeatPosition.MIDDLE
                    }
                    var from = i
                    while (from > 0 && row.cells[from - 1] is Cell.SeatCell) from--
                    var to = i
                    while (to < row.cells.lastIndex && row.cells[to + 1] is Cell.SeatCell) to++
                    val neighbours = (from..to).filter { it != i }.map { (row.cells[it] as Cell.SeatCell).code }
                    return SeatSpot(section, row, position, neighbours)
                }
            }
        }
        return null
    }
}
