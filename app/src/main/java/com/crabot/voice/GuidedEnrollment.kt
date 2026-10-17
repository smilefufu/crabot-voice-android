package com.crabot.voice

/** Ephemeral capture permission; never persisted or inferred from a business playback. */
class GuidedEnrollment(val operationId: String, val epoch: String, val deadline: Long, val delivery: String = "origin") {
    init { require(delivery in listOf("origin", "requester_private")) }
    var step = 0
        private set
    private var playbackId: String? = null
    private var expectedSamples: Int? = null
    private var completedSamples: Int? = null
    private var recording = false
    var sampled = false
        private set
    fun prompt(nextStep: Int, id: String, now: Long) {
        require(!sampled && now < deadline && nextStep in 1..3 && (nextStep == step || nextStep == step + 1))
        step = nextStep; playbackId = id; expectedSamples = null; completedSamples = null; recording = false
    }
    fun ended(id: String, samples: Int) {
        require(id == playbackId && samples in 1..Wire.MAX_OUTPUT)
        expectedSamples = samples
    }
    fun played(id: String, samples: Int) {
        require(id == playbackId && samples == expectedSamples)
        completedSamples = samples
    }
    fun record(id: String, nextStep: Int, currentEpoch: String?, foreground: Boolean, connected: Boolean, now: Long) {
        require(!sampled && !recording && id == playbackId && step == nextStep && epoch == currentEpoch && foreground && connected
            && now < deadline && expectedSamples != null && completedSamples == expectedSamples)
        recording = true
    }
    fun saved() { require(step == 3 && recording); sampled = true }
}
