package de.wanderlogplus.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class ThrottleTest {
    private val sleeps = mutableListOf<Long>()

    private fun run(vararg answers: Attempt): Pair<Attempt, Int> {
        var calls = 0
        val r = throttled(sleep = { sleeps += it }) { answers[minOf(calls++, answers.size - 1)] }
        return r to calls
    }

    @Test
    fun retriesOn429ThenOk() {
        val (r, calls) = run(Attempt(429, "", "2"), Attempt(200, "ok", null))
        assertEquals(200, r.status)
        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun inFlightBudget402Retries() {
        val body = """{"error":{"metadata":{"limit_source":"openrouter_in_flight_budget"}}}"""
        val (r, _) = run(Attempt(402, body, null), Attempt(200, "ok", null))
        assertEquals(200, r.status)
        assertEquals(listOf(10_000L), sleeps)
    }

    @Test
    fun plain402DoesNotRetry() {
        val (r, calls) = run(Attempt(402, """{"error":{"metadata":{"limit_source":"openrouter_credits"}}}""", null))
        assertEquals(402, r.status)
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun givesUpAfterThree() {
        val (r, calls) = run(Attempt(429, "", "1"))
        assertEquals(429, r.status)
        assertEquals(4, calls)
        assertEquals(3, sleeps.size)
    }

    @Test
    fun clampsRetryAfter() {
        run(Attempt(429, "", "600"), Attempt(429, "", "x"), Attempt(200, "", null))
        assertEquals(listOf(60_000L, 10_000L), sleeps)
    }
}
