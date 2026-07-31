package com.selenite.scanner.link

/**
 * Optional BLE observations used only to break a tie between several nearby peers.
 *
 * Nearby exposes no RSSI for its discovered endpoints, so Link advertises one ephemeral
 * service UUID derived from its session nonce and looks for the UUIDs its candidates
 * would be advertising. Everything here is best-effort: if the platform refuses to
 * advertise, refuses to scan, or produces too few samples, the resolver abstains and the
 * nickname chooser is shown instead.
 *
 * The beacon carries no stable identity, and scanning only ever starts once more than
 * one compatible peer is present.
 */
internal interface MirorLinkProximitySource {
    /** False when BLE is missing, powered off, or the permission was not granted. */
    fun isAvailable(): Boolean

    suspend fun startBeacon(serviceUuid: String)
    suspend fun stopBeacon()

    /** Scans for exactly the candidate UUIDs supplied; never an unfiltered scan. */
    suspend fun startScan(serviceUuids: List<String>, onSample: (uuid: String, rssiDbm: Int) -> Unit)
    suspend fun stopScan()
}

/** Null where no BLE ranging is available; the chooser then handles every ambiguity. */
internal expect fun createMirorLinkProximitySource(): MirorLinkProximitySource?
