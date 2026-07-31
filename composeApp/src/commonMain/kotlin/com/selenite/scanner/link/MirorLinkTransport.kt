package com.selenite.scanner.link

import kotlinx.coroutines.flow.Flow

/**
 * Small binary blob advertised alongside a Nearby endpoint.
 *
 * Nearby's `endpointInfo` is tightly bounded (131 bytes on Android), so this carries
 * only what selection needs before a connection exists: who can talk to whom, an
 * ephemeral nonce to select on, and a nickname to show if selection stays ambiguous.
 *
 * It deliberately contains **no stable device identifier**. The nonce is regenerated
 * on every Link entry and never persisted, so two sessions from the same phone are
 * not linkable from the air.
 */
internal data class MirorLinkEndpointInfo(
    val protocolMin: Int,
    val protocolMax: Int,
    val nonce: ByteArray,
    val supportsRawManifest: Boolean,
    val contentServe: Boolean,
    val contentInstall: Boolean,
    val hasSnapshot: Boolean,
    val nickname: String,
) {
    fun encode(): ByteArray {
        require(nonce.size == MirorLinkProtocol.NONCE_BYTES) { "Link nonce must be 16 bytes" }
        val nicknameBytes = nickname.encodeToByteArray()
        require(nicknameBytes.size <= MirorLinkProtocol.MAX_NICKNAME_BYTES) {
            "Link nickname exceeds its byte bound"
        }
        var capabilities = 0
        if (supportsRawManifest) capabilities = capabilities or CAP_RAW_MANIFEST
        if (contentServe) capabilities = capabilities or CAP_CONTENT_SERVE
        if (contentInstall) capabilities = capabilities or CAP_CONTENT_INSTALL
        if (hasSnapshot) capabilities = capabilities or CAP_HAS_SNAPSHOT

        val writer = MirorLinkWriter(MAX_ENCODED_BYTES)
        writer.writeBytes(MAGIC)
        writer.writeByte(protocolMin)
        writer.writeByte(protocolMax)
        writer.writeByte(capabilities)
        writer.writeBytes(nonce)
        writer.writeByte(nicknameBytes.size)
        writer.writeBytes(nicknameBytes)
        val encoded = writer.toByteArray()
        check(encoded.size <= MAX_ENCODED_BYTES) { "Link endpoint info exceeds its advertising budget" }
        return encoded
    }

    override fun equals(other: Any?): Boolean =
        other is MirorLinkEndpointInfo && protocolMin == other.protocolMin &&
            protocolMax == other.protocolMax && nonce.contentEquals(other.nonce) &&
            supportsRawManifest == other.supportsRawManifest &&
            contentServe == other.contentServe && contentInstall == other.contentInstall &&
            hasSnapshot == other.hasSnapshot && nickname == other.nickname

    override fun hashCode(): Int {
        var result = protocolMin
        result = 31 * result + protocolMax
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + supportsRawManifest.hashCode()
        result = 31 * result + contentServe.hashCode()
        result = 31 * result + contentInstall.hashCode()
        result = 31 * result + hasSnapshot.hashCode()
        result = 31 * result + nickname.hashCode()
        return result
    }

    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'L'.code.toByte())
        private const val CAP_RAW_MANIFEST = 1
        private const val CAP_CONTENT_SERVE = 2
        private const val CAP_CONTENT_INSTALL = 4
        private const val CAP_HAS_SNAPSHOT = 8

        /** Well under Nearby's 131-byte Android endpointInfo limit. */
        const val MAX_ENCODED_BYTES = 96

        fun decode(bytes: ByteArray): MirorLinkEndpointInfo? = try {
            linkRequire(bytes.size <= MAX_ENCODED_BYTES) { "Link endpoint info is too long" }
            val reader = MirorLinkReader(bytes)
            val magic = reader.readBytes(2)
            linkRequire(magic.contentEquals(MAGIC)) { "Not a Miror Link endpoint" }
            val min = reader.readByte()
            val max = reader.readByte()
            linkRequire(min <= max) { "Link endpoint protocol range is inverted" }
            val capabilities = reader.readByte()
            val nonce = reader.readBytes(MirorLinkProtocol.NONCE_BYTES)
            val nicknameLength = reader.readByte()
            linkRequire(nicknameLength <= MirorLinkProtocol.MAX_NICKNAME_BYTES) {
                "Link endpoint nickname is too long"
            }
            val nickname = MirorLinkProtocol.truncateNicknameToBounds(
                MirorLinkProtocol.sanitizeNickname(
                    reader.readBytes(nicknameLength).decodeToString(throwOnInvalidSequence = false),
                ),
            )
            linkRequire(reader.exhausted) { "Link endpoint info has trailing bytes" }
            MirorLinkEndpointInfo(
                protocolMin = min,
                protocolMax = max,
                nonce = nonce,
                supportsRawManifest = capabilities and CAP_RAW_MANIFEST != 0,
                contentServe = capabilities and CAP_CONTENT_SERVE != 0,
                contentInstall = capabilities and CAP_CONTENT_INSTALL != 0,
                hasSnapshot = capabilities and CAP_HAS_SNAPSHOT != 0,
                nickname = nickname,
            )
        } catch (_: MirorLinkProtocolException) {
            // A foreign or corrupt advertisement is simply not a Miror peer. It must not
            // fail the whole discovery pass.
            null
        }
    }
}

internal sealed interface MirorLinkTransportEvent {
    data class PeerFound(val endpointId: String, val info: MirorLinkEndpointInfo) : MirorLinkTransportEvent
    data class PeerLost(val endpointId: String) : MirorLinkTransportEvent

    /**
     * The SDK is asking whether to complete a connection. Both platforms require the
     * callback to be answered exactly once; v1 answers it automatically after the
     * session/selection checks pass, which is not a user-visible pairing step.
     */
    data class ConnectionInitiated(
        val endpointId: String,
        val info: MirorLinkEndpointInfo?,
        val isIncoming: Boolean,
    ) : MirorLinkTransportEvent

    data class Connected(val endpointId: String) : MirorLinkTransportEvent
    data class ConnectionFailed(val endpointId: String, val reason: String) : MirorLinkTransportEvent
    data class Disconnected(val endpointId: String) : MirorLinkTransportEvent
    data class FrameReceived(val endpointId: String, val frame: ByteArray) : MirorLinkTransportEvent
    data class TransportFailure(val reason: String, val fatal: Boolean) : MirorLinkTransportEvent
}

internal enum class MirorLinkAvailability {
    Available,

    /** Google Play services missing/disabled/outdated, or the platform lacks support. */
    Unsupported,

    /** Available once the user grants the in-context permissions. */
    PermissionsRequired,

    /** Bluetooth or Wi-Fi is switched off. */
    RadiosOff,
}

/**
 * Platform discovery/transport surface. Advertising and discovery are separately
 * stoppable because the plan requires stopping discovery the moment a peer is
 * selected while keeping advertising alive until the peer's reciprocal selection is
 * evidenced -- continuing discovery destabilises connection setup, but stopping
 * advertising too early strands the higher-nonce side.
 */
internal interface MirorLinkTransport {
    val events: Flow<MirorLinkTransportEvent>

    suspend fun availability(): MirorLinkAvailability

    suspend fun startAdvertising(endpointInfo: ByteArray)
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun stopAdvertising()

    suspend fun requestConnection(endpointId: String, endpointInfo: ByteArray)
    suspend fun acceptConnection(endpointId: String)
    suspend fun rejectConnection(endpointId: String)

    suspend fun send(endpointId: String, frame: ByteArray)
    suspend fun disconnect(endpointId: String)

    /** Stops everything and releases radios. Must be safe to call repeatedly. */
    suspend fun shutdown()
}
