package com.selenite.scanner.link

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Implemented in Swift by `MirorLinkService`, which owns Google's Nearby Connections
 * types.
 *
 * The Swift package's types are not pushed through Kotlin/Native interop; the Swift side
 * owns them and speaks to Kotlin only in primitives and byte arrays. This follows the
 * app's existing native-bridge pattern and keeps the Nearby dependency entirely on the
 * Swift side of the boundary.
 */
interface MirorLinkNativeBridge {
    fun isAvailable(): Boolean
    fun startAdvertising(endpointInfo: ByteArray, serviceId: String)
    fun startDiscovery(serviceId: String)
    fun stopAdvertising()
    fun stopDiscovery()
    fun requestConnection(endpointId: String, endpointInfo: ByteArray)
    fun acceptConnection(endpointId: String)
    fun rejectConnection(endpointId: String)
    fun send(endpointId: String, frame: ByteArray)
    fun disconnect(endpointId: String)
    fun shutdown()
    fun setListener(listener: MirorLinkNativeListener?)
}

/** Implemented in Kotlin, called from Swift as Nearby's delegates fire. */
interface MirorLinkNativeListener {
    fun onEndpointFound(endpointId: String, endpointInfo: ByteArray)
    fun onEndpointLost(endpointId: String)
    fun onConnectionInitiated(endpointId: String, endpointInfo: ByteArray?, incoming: Boolean)
    fun onConnected(endpointId: String)
    fun onConnectionFailed(endpointId: String, reason: String)
    fun onDisconnected(endpointId: String)
    fun onFrameReceived(endpointId: String, frame: ByteArray)
    fun onFailure(reason: String, fatal: Boolean)
}

/**
 * Set once from Swift during app start. Until the Nearby Swift package is actually wired
 * into the Xcode project this stays null, and Link reports itself unsupported on iOS
 * rather than pretending to work.
 */
object MirorLinkBridgeRegistry {
    var bridge: MirorLinkNativeBridge? = null
}

internal class IosMirorLinkTransport private constructor(
    private val bridge: MirorLinkNativeBridge,
) : MirorLinkTransport, MirorLinkNativeListener {

    private val _events = MutableSharedFlow<MirorLinkTransportEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        // Preserve the ordering of already-accepted state-machine events. Under a hostile
        // burst, dropping the newest event is safer than evicting an earlier HELLO or
        // connection transition and delivering later chunks without their prerequisite.
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<MirorLinkTransportEvent> = _events.asSharedFlow()

    init {
        bridge.setListener(this)
    }

    override suspend fun availability(): MirorLinkAvailability =
        if (bridge.isAvailable()) {
            MirorLinkAvailability.Available
        } else {
            // iOS has no API to request or reliably query Local Network authorization, so
            // denial is inferred from the transport failing to start rather than probed.
            MirorLinkAvailability.PermissionsRequired
        }

    override suspend fun startAdvertising(endpointInfo: ByteArray) =
        bridge.startAdvertising(endpointInfo, AndroidServiceIdParity.SERVICE_ID)

    override suspend fun startDiscovery() =
        bridge.startDiscovery(AndroidServiceIdParity.SERVICE_ID)

    override suspend fun stopDiscovery() = bridge.stopDiscovery()

    override suspend fun stopAdvertising() = bridge.stopAdvertising()

    override suspend fun requestConnection(endpointId: String, endpointInfo: ByteArray) =
        bridge.requestConnection(endpointId, endpointInfo)

    override suspend fun acceptConnection(endpointId: String) = bridge.acceptConnection(endpointId)

    override suspend fun rejectConnection(endpointId: String) = bridge.rejectConnection(endpointId)

    override suspend fun send(endpointId: String, frame: ByteArray) {
        require(frame.size <= MirorLinkProtocol.MAX_FRAME_BYTES) {
            "Link frame exceeds the transport budget"
        }
        bridge.send(endpointId, frame)
    }

    override suspend fun disconnect(endpointId: String) = bridge.disconnect(endpointId)

    override suspend fun shutdown() = bridge.shutdown()

    // ------------------------------------------------------------- native callbacks

    override fun onEndpointFound(endpointId: String, endpointInfo: ByteArray) {
        val decoded = MirorLinkEndpointInfo.decode(endpointInfo) ?: return
        _events.tryEmit(MirorLinkTransportEvent.PeerFound(endpointId, decoded))
    }

    override fun onEndpointLost(endpointId: String) {
        _events.tryEmit(MirorLinkTransportEvent.PeerLost(endpointId))
    }

    override fun onConnectionInitiated(
        endpointId: String,
        endpointInfo: ByteArray?,
        incoming: Boolean,
    ) {
        _events.tryEmit(
            MirorLinkTransportEvent.ConnectionInitiated(
                endpointId = endpointId,
                info = endpointInfo?.let { MirorLinkEndpointInfo.decode(it) },
                isIncoming = incoming,
            ),
        )
    }

    override fun onConnected(endpointId: String) {
        _events.tryEmit(MirorLinkTransportEvent.Connected(endpointId))
    }

    override fun onConnectionFailed(endpointId: String, reason: String) {
        _events.tryEmit(MirorLinkTransportEvent.ConnectionFailed(endpointId, reason))
    }

    override fun onDisconnected(endpointId: String) {
        _events.tryEmit(MirorLinkTransportEvent.Disconnected(endpointId))
    }

    override fun onFrameReceived(endpointId: String, frame: ByteArray) {
        if (frame.size > MirorLinkProtocol.MAX_FRAME_BYTES) return
        _events.tryEmit(MirorLinkTransportEvent.FrameReceived(endpointId, frame))
    }

    override fun onFailure(reason: String, fatal: Boolean) {
        _events.tryEmit(MirorLinkTransportEvent.TransportFailure(reason, fatal))
    }

    companion object {
        fun createIfBridgeAvailable(): MirorLinkTransport? =
            MirorLinkBridgeRegistry.bridge?.let { IosMirorLinkTransport(it) }
    }
}

/**
 * The service id is a cross-platform discovery contract, so both platforms must use the
 * exact same string. It is restated here rather than imported because the Android
 * transport lives in `androidMain`; the release checklist verifies the two agree.
 */
internal object AndroidServiceIdParity {
    const val SERVICE_ID = "com.selenite.scanner.mirorlink"
}

/**
 * Null on iOS for now: proximity ranking would need a Core Bluetooth advertiser and
 * scanner alongside Nearby's own radio use, and until that is measured on hardware the
 * conservative behaviour -- always show the nickname chooser when several peers are
 * present -- is the correct one.
 */
internal actual fun createMirorLinkProximitySource(): MirorLinkProximitySource? = null
