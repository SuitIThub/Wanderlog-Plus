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
    /** What the server answers on each poll of the recent guide jobs, in order; the last one repeats. */
    private var serverJobs = mutableListOf<List<AiJobInfo>>(emptyList())
    private var syncs = 0
    private val vm = GuidesViewModel(
        edit, CoroutineScope(Dispatchers.Unconfined),
        startGuides = { asks -> asked += asks; AiJobInfo("j1", "guides", 0, "QUEUED") },
        recentJobs = { if (serverJobs.size > 1) serverJobs.removeAt(0) else serverJobs.first() },
        syncNow = { syncs++ },
        pollMs = 0,
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
        serverJobs = mutableListOf(listOf(AiJobInfo("j1", "guides", 0, "QUEUED")), listOf(AiJobInfo("j1", "guides", 0, "DONE")))
        vm.createSelected(listOf("onsen", "ryokan"))
        assertEquals(listOf(listOf(GuideAsk(topic = "onsen"), GuideAsk(topic = "ryokan"))), asked)
        assertEquals("j1", vm.jobs.value.single().id)
    }

    @Test
    fun jobsStartedElsewhereAreFollowedUntilTheyEnd() {
        val running = AiJobInfo("chat-job", "guides", 0, "RESEARCHING", guideIds = listOf("g1"))
        serverJobs = mutableListOf(listOf(running), listOf(running), listOf(running.copy(status = "DONE")))
        vm.refreshJobs()
        assertEquals("DONE", vm.jobs.value.single().status)
        // The rewritten guide comes with the next sync.
        assertEquals(1, syncs)
    }

    @Test
    fun finishedJobsAreNotPolled() {
        serverJobs = mutableListOf(listOf(AiJobInfo("old", "guides", 0, "DONE")))
        vm.refreshJobs()
        assertEquals("old", vm.jobs.value.single().id)
        assertEquals(0, syncs)
    }

    @Test
    fun saveAndDelete() {
        seedGuide(Guide("g1", topic = "onsen", title = "Onsen"))
        vm.save(snapshot().guides.single().copy(title = "Onsen-Knigge"))
        assertEquals("Onsen-Knigge", snapshot().guides.single().title)
        vm.delete("g1")
        assertTrue(snapshot().guides.isEmpty())
    }

    @Test
    fun addLinksStartsJobAndFollowsIt() {
        var started = 0
        val running = AiJobInfo("l1", "guides", 5, "RESEARCHING")
        val links = GuidesViewModel(
            edit, CoroutineScope(Dispatchers.Unconfined),
            startGuides = { null }, recentJobs = { listOf(running.copy(status = "DONE")) },
            startGuideLinks = { started++; running }, pollMs = 0,
        )
        links.addLinks()
        assertEquals(1, started)
        assertTrue(links.jobs.value.any { it.id == "l1" })
        assertEquals("Links werden ergänzt …", links.message.value)
    }

    @Test
    fun addLinksWithoutServerSaysSo() {
        val links = GuidesViewModel(edit, CoroutineScope(Dispatchers.Unconfined), startGuides = { null }, recentJobs = { null }, startGuideLinks = { null }, pollMs = 0)
        links.addLinks()
        assertEquals("Links lassen sich gerade nicht ergänzen (Server nicht erreichbar?).", links.message.value)
    }

    @Test
    fun tidyStartsTheJobForOneGuide() {
        val asked = mutableListOf<List<String>>()
        val running = AiJobInfo("t1", "guides", 5, "RESEARCHING", guideIds = listOf("g1"))
        val vm = GuidesViewModel(
            edit, CoroutineScope(Dispatchers.Unconfined),
            startGuides = { null }, recentJobs = { listOf(running.copy(status = "DONE")) },
            startGuideTidy = { ids -> asked += ids; running }, pollMs = 0,
        )
        vm.tidy(listOf("g1"))
        assertEquals(listOf(listOf("g1")), asked)
        assertTrue(vm.jobs.value.any { it.id == "t1" })
        assertEquals("Guides werden gestrafft …", vm.message.value)
    }

    @Test
    fun tidyWithoutServerSaysSo() {
        val vm = GuidesViewModel(edit, CoroutineScope(Dispatchers.Unconfined), startGuides = { null }, recentJobs = { null }, startGuideTidy = { null }, pollMs = 0)
        vm.tidy(emptyList())
        assertEquals("Guides lassen sich gerade nicht straffen (Server nicht erreichbar?).", vm.message.value)
    }

    @Test
    fun revertBringsTheOldWordingBackAndListsTheCorrectionAsTakenBack() {
        val corrected = Guide(
            "g1", title = "Kleinigkeiten",
            sections = listOf(de.wanderlogplus.shared.model.GuideSection("Essen", donts = listOf("Wo ein Schild es verlangt, nicht im Gehen essen"))),
            notes = listOf(de.wanderlogplus.shared.model.GuideNote("Wo ein Schild es verlangt, nicht im Gehen essen", "uneins")),
        )
        seedGuide(corrected)
        val c = de.wanderlogplus.shared.sync.GuideCorrection(
            "g1", kind = de.wanderlogplus.shared.model.StatementKind.DONT, heading = "Essen", before = "Nie im Gehen essen",
            after = "Wo ein Schild es verlangt, nicht im Gehen essen", verdict = de.wanderlogplus.shared.sync.TipVerdict.PARTLY, note = "uneins",
        )
        val job = AiJobInfo("j1", "guides", 1, "DONE", source = "check", corrections = listOf(c, c))
        assertEquals(listOf(c), GuideCorrections.of(listOf(job), "g1"))
        assertEquals("1 Korrektur", GuideCorrections.summary(listOf(c)))
        assertTrue(GuideCorrections.isCheck(job))
        vm.revert(c)
        val back = snapshot().guides.single()
        assertEquals(listOf("Nie im Gehen essen"), back.sections.single().donts)
        assertEquals(emptyList<de.wanderlogplus.shared.model.GuideNote>(), back.notes)
        assertEquals(de.wanderlogplus.shared.guide.CorrectionState.REVERTED, de.wanderlogplus.shared.guide.GuideStatements.stateOf(back, c))
    }

    @Test
    fun checkStartsTheJob() {
        val checks = mutableListOf<Pair<List<String>, Boolean>>()
        val v = GuidesViewModel(
            // The server lists the started check as done right away, so following it ends.
            edit, CoroutineScope(Dispatchers.Unconfined), startGuides = { null }, recentJobs = { listOf(AiJobInfo("c1", "guides", 0, "DONE", source = "check")) }, pollMs = 0,
            startGuideCheck = { ids, thorough -> checks += ids to thorough; AiJobInfo("c1", "guides", 0, "QUEUED", source = "check") },
        )
        v.check(listOf("g1"), thorough = true)
        assertEquals(listOf(listOf("g1") to true), checks)
        assertEquals("Guide wird gründlich geprüft …", v.message.value)
    }
}
