package de.wanderlogplus.app.ui.logistics

import de.wanderlogplus.shared.sync.AiJobInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class StayInfoViewModelTest {
    @Test
    fun researchStartsTheJobFollowsItAndSyncs() = runBlocking {
        val started = mutableListOf<Triple<String, List<String>, Boolean>>()
        var polls = 0
        var synced = 0
        val vm = StayInfoViewModel(
            CoroutineScope(Dispatchers.Default),
            start = { id, links, fresh -> started += Triple(id, links, fresh); AiJobInfo("j1", "stay", 0, "QUEUED", stayId = id) },
            job = { id -> polls++; AiJobInfo(id, "stay", 0, if (polls < 2) "RESEARCHING" else "DONE", stayId = "st") },
            syncNow = { synced++ },
            pollMillis = 1,
        )
        vm.research("st", listOf("https://ryokan.example"), fresh = true)
        withTimeout(5_000) { while (vm.running.value?.status != "DONE") kotlinx.coroutines.delay(5) }
        assertEquals(listOf(Triple("st", listOf("https://ryokan.example"), true)), started)
        assertEquals(1, synced)
        assertEquals("st", vm.running.value?.stayId)
    }
}
