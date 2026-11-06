package de.wanderlogplus.app.ui.guides

import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.GuideAsk
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidesViewModelTest {
    private val store = InMemoryEntityStore()
    private val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
    private val edit: EditFn = { block -> block(editor, snapshot()) }
    private val asked = mutableListOf<List<GuideAsk>>()
    private val vm = GuidesViewModel(
        edit, CoroutineScope(Dispatchers.Unconfined),
        startGuides = { asks -> asked += asks; AiJobInfo("j1", "guides", 0, "QUEUED") },
        job = { null },
    )

    private fun snapshot() = TripSnapshot.from(store.all())

    private val place = GuideTarget(GuideTargetType.PLACE, "s1")

    private fun seedGuide(g: Guide) {
        editor.create(EntityType.GUIDE, EntityCodec.fields(g), id = g.id)
    }

    @Test
    fun dismissRemembersTopics() {
        vm.dismiss("burg")
        vm.dismiss("onsen")
        vm.dismiss("burg")
        assertEquals(listOf("burg", "onsen"), snapshot().guidePrefs.dismissedTopics)
    }

    @Test
    fun hideAndPinMoveTheTarget() {
        seedGuide(Guide("g1", topic = "onsen", title = "Onsen", pinnedTo = listOf(place)))
        vm.hide("g1", place)
        val hidden = snapshot().guides.single()
        assertEquals(emptyList<GuideTarget>(), hidden.pinnedTo)
        assertEquals(listOf(place), hidden.hiddenFrom)
        vm.pin("g1", place)
        val pinned = snapshot().guides.single()
        assertEquals(listOf(place), pinned.pinnedTo)
        assertEquals(emptyList<GuideTarget>(), pinned.hiddenFrom)
    }

    @Test
    fun createSelectedAsksForEachTopic() {
        vm.createSelected(listOf("onsen", "ryokan"))
        assertEquals(listOf(listOf(GuideAsk(topic = "onsen"), GuideAsk(topic = "ryokan"))), asked)
        assertEquals("j1", vm.running.value?.id)
    }

    @Test
    fun saveAndDelete() {
        seedGuide(Guide("g1", topic = "onsen", title = "Onsen"))
        vm.save(snapshot().guides.single().copy(title = "Onsen-Knigge"))
        assertEquals("Onsen-Knigge", snapshot().guides.single().title)
        vm.delete("g1")
        assertTrue(snapshot().guides.isEmpty())
    }
}
