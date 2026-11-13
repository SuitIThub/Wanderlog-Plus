package de.wanderlogplus.app.widgets

import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetTargetTest {
    @Test fun everyTargetRoundTrips() {
        listOf(
            WidgetTarget.Today, WidgetTarget.Day("2027-04-02"), WidgetTarget.Logistics(EntityType.TRAIN, "tr-1"),
            WidgetTarget.Attachment("a-1"), WidgetTarget.NewExpense, WidgetTarget.StayAddress("st-1"),
            WidgetTarget.Place("s-1"), WidgetTarget.Chat(null), WidgetTarget.Chat("c-1"), WidgetTarget.Ask("food"),
        ).forEach { assertEquals(it, WidgetTarget.parse(it.encode())) }
    }

    @Test fun unknownTargetsAreNull() {
        assertNull(WidgetTarget.parse(null))
        assertNull(WidgetTarget.parse("x:1"))
        assertNull(WidgetTarget.parse("logistics:BOAT:1"))
        assertNull(WidgetTarget.parse("ask:dance"))
        assertNull(WidgetTarget.parse("day:"))
    }
}
