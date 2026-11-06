package de.wanderlogplus.server.ai

/** One HTTP answer: status, body and the Retry-After header (seconds), if any. */
data class Attempt(val status: Int, val body: String, val retryAfter: String?)

/** True for answers that mean "too much at once, try again shortly": 429 and OpenRouter's in-flight budget (402). */
fun Attempt.isThrottled(): Boolean = status == 429 || (status == 402 && "openrouter_in_flight_budget" in body)

/**
 * Runs [call] and repeats it up to [maxRetries] times while the answer is throttled, waiting Retry-After seconds
 * (1–60, default 10) in between. Returns the last answer; the caller decides what a non-2xx answer means.
 */
fun throttled(sleep: (Long) -> Unit = Thread::sleep, maxRetries: Int = 3, call: () -> Attempt): Attempt {
    var attempt = call()
    var retries = 0
    while (attempt.isThrottled() && retries < maxRetries) {
        val seconds = attempt.retryAfter?.trim()?.toLongOrNull()?.coerceIn(1, 60) ?: 10
        sleep(seconds * 1000)
        retries++
        attempt = call()
    }
    return attempt
}
