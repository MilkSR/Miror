package com.selenite.scanner.link

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * One BLE observation waiting to enter the coordinator's serialized state.
 *
 * Android delivers scan callbacks on a platform callback thread while Link's discovery
 * loop runs on its process coroutine scope. Carrying the session generation with the
 * observation lets the coordinator discard a callback that crossed teardown/restart.
 */
internal data class MirorLinkProximitySample(
    val generation: Int,
    val scanEpoch: Int,
    val nonceKey: String,
    val rssiDbm: Int,
    val atMillis: Long,
)

/**
 * Thread-safe, bounded bridge from platform scan callbacks into coordinator state.
 *
 * A callback must never mutate [MirorLinkProximityResolver] directly: `decide` and
 * `clear` run under the coordinator mutex, and concurrent mutation could either crash or
 * make an uncertain signal look decisive. A radio-range peer can advertise rapidly, so
 * this bridge also cannot grow with callback volume. Keeping the newest observations is
 * the conservative choice because the resolver already ignores samples outside its
 * short time window.
 */
internal class MirorLinkProximitySampleInbox(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val samples = Channel<MirorLinkProximitySample>(
        capacity = capacity.also { require(it > 0) },
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Safe to call from an arbitrary platform callback thread; never blocks it. */
    fun offer(sample: MirorLinkProximitySample): Boolean = samples.trySend(sample).isSuccess

    suspend fun receive(): MirorLinkProximitySample = samples.receive()

    /** Used at session teardown so queued observations do not occupy the next session. */
    fun discardPending() {
        while (samples.tryReceive().isSuccess) Unit
    }

    companion object {
        /**
         * More than two complete minimum-sample rounds for all 32 tracked peers.
         *
         * Losing observations only makes the resolver abstain and show the chooser; it can
         * never make an incompletely sampled peer eligible for automatic selection.
         */
        const val DEFAULT_CAPACITY = 256
    }
}

internal sealed interface MirorLinkProximityDecision {
    /** One peer is both very close in absolute terms and clearly ahead of the runner-up. */
    data class Confident(val nonceKey: String) : MirorLinkProximityDecision

    /** Signals are close, noisy, or contradictory. Show the nickname chooser. */
    data object Ambiguous : MirorLinkProximityDecision

    /** No BLE, no permission, or too few samples. Show the nickname chooser. */
    data object Unavailable : MirorLinkProximityDecision
}

/**
 * Derives the ephemeral BLE service UUID a Link-active phone beacons.
 *
 * The UUID is a hash of a Miror domain separator and the session nonce, so it is 128
 * bits that fit the 31-byte legacy advertising budget on their own -- no manufacturer
 * ID, no second key. It carries no stable identity: a fresh nonce each session means
 * the beacon cannot be correlated across sessions.
 */
internal object MirorLinkBeacon {
    private const val DOMAIN_SEPARATOR = "miror.link.beacon.v1"

    fun serviceUuidBytes(nonce: ByteArray): ByteArray {
        require(nonce.size == MirorLinkProtocol.NONCE_BYTES) { "Link nonce must be 16 bytes" }
        val digest = MirorSha256().apply {
            update(DOMAIN_SEPARATOR.encodeToByteArray())
            update(nonce)
        }.digest()
        return digest.copyOfRange(0, 16)
    }

    /** Canonical 8-4-4-4-12 text form, for platform APIs that want a UUID string. */
    fun serviceUuidString(nonce: ByteArray): String {
        val hex = serviceUuidBytes(nonce).toHexLowercase()
        return buildString(36) {
            append(hex, 0, 8).append('-')
            append(hex, 8, 12).append('-')
            append(hex, 12, 16).append('-')
            append(hex, 16, 20).append('-')
            append(hex, 20, 32)
        }
    }
}

/**
 * Conservative RSSI ranking.
 *
 * Nearby exposes no RSSI for its discovered endpoints, so Link associates optional
 * BLE observations with a Nearby session through the beacon UUID above. RSSI is
 * affected by cases, pockets, orientation, bodies, and radio hardware, so this is
 * built to abstain: every uncertain outcome returns [MirorLinkProximityDecision.Ambiguous]
 * and the user picks a nickname instead. It is never allowed to be the only thing
 * standing between two strangers' collections.
 *
 * Thresholds are runtime constants, not wire values, so physical calibration can move
 * them without any compatibility consequence.
 */
internal class MirorLinkProximityResolver(
    private val veryCloseRssiDbm: Int = DEFAULT_VERY_CLOSE_DBM,
    private val requiredMarginDb: Int = DEFAULT_MARGIN_DB,
    private val minSamplesPerPeer: Int = DEFAULT_MIN_SAMPLES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private data class Sample(val rssi: Int, val atMillis: Long)

    private val samples = mutableMapOf<String, MutableList<Sample>>()
    private var scanningAvailable = true

    fun setScanningAvailable(available: Boolean) {
        scanningAvailable = available
        if (!available) samples.clear()
    }

    fun record(nonceKey: String, rssiDbm: Int, atMillis: Long) {
        if (!scanningAvailable) return
        val list = samples.getOrPut(nonceKey) { mutableListOf() }
        list += Sample(rssiDbm, atMillis)
        if (list.size > MAX_SAMPLES_PER_PEER) list.removeAt(0)
    }

    fun clear() = samples.clear()

    fun decide(candidateKeys: Collection<String>, nowMillis: Long): MirorLinkProximityDecision {
        if (!scanningAvailable) return MirorLinkProximityDecision.Unavailable
        if (candidateKeys.size < 2) return MirorLinkProximityDecision.Unavailable

        val medians = candidateKeys.mapNotNull { key ->
            val recent = samples[key]
                ?.filter { nowMillis - it.atMillis <= windowMillis }
                ?.map { it.rssi }
                .orEmpty()
            if (recent.size < minSamplesPerPeer) null else key to median(recent)
        }

        // A peer we have no usable samples for could easily be the closest one. Ranking
        // on partial information is exactly how a wrong automatic link happens.
        if (medians.size != candidateKeys.size) return MirorLinkProximityDecision.Unavailable

        val ranked = medians.sortedByDescending { it.second }
        val (bestKey, bestRssi) = ranked[0]
        val runnerUpRssi = ranked[1].second

        if (bestRssi < veryCloseRssiDbm) return MirorLinkProximityDecision.Ambiguous
        if (bestRssi - runnerUpRssi < requiredMarginDb) return MirorLinkProximityDecision.Ambiguous
        return MirorLinkProximityDecision.Confident(bestKey)
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    companion object {
        /**
         * Anchored to Apple's documented Core Bluetooth guidance rather than guessed.
         *
         * Apple's BTLE_Transfer central sample gates connection on RSSI directly:
         *
         *     // Reject any where the value is above reasonable range
         *     if (RSSI.integerValue > -15) { return; }
         *     // Reject if the signal strength is too low to be close enough
         *     // (Close is around -22dB)
         *     if (RSSI.integerValue < -35) { return; }
         *
         * So Apple treats roughly -35..-15 dBm as "close", centred near -22 dBm, for
         * phone-to-phone BLE. Miror widens that floor by about 10 dB to -45, because
         * Android advertisers vary in TX power, cases attenuate, and a body between two
         * phones costs several dB -- iPhone-to-iPhone numbers do not transfer unchanged.
         *
         * The previous value here was -55, which is roughly a metre or two of separation:
         * far too permissive to mean "phones together", and the wrong direction to err in
         * when the failure mode is linking to a stranger. -45 abstains more often, which
         * is what the plan asks for while calibration is outstanding.
         *
         * Still worth confirming against physical traces before relying on it; the point
         * of this constant is that it is now derived and citable rather than invented.
         */
        const val DEFAULT_VERY_CLOSE_DBM = -45
        const val DEFAULT_MARGIN_DB = 10
        const val DEFAULT_MIN_SAMPLES = 3
        const val DEFAULT_WINDOW_MILLIS = 2_500L
        private const val MAX_SAMPLES_PER_PEER = 32
    }
}
