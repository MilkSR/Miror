import Foundation
import ComposeApp

// Google's Nearby Connections Swift package. Until it is added to iosApp.xcodeproj
// on a Mac, this file still compiles and Miror Link reports itself unsupported.
//
// The import is conditional so the app still builds and runs -- with Link reporting
// itself unsupported -- before the package has been added on a Mac. Once it resolves,
// MirorLinkBridgeRegistry gets a live bridge and Link turns on.
#if canImport(NearbyConnections)
import NearbyConnections

/// Owns Nearby's connection manager and translates its delegate callbacks into the
/// Kotlin listener contract. Nothing from the Nearby package crosses into Kotlin: the
/// boundary is strings and byte arrays only.
final class MirorLinkService: NSObject, MirorLinkNativeBridge {

    private var listener: MirorLinkNativeListener?

    private var connectionManager: ConnectionManager?
    private var advertiser: Advertiser?
    private var discoverer: Discoverer?

    /// Endpoint info seen for each endpoint, so a connection request can be validated
    /// against the nonce the peer advertised.
    private var endpointInfo: [String: Data] = [:]

    /// Nearby requires the verification handler to be invoked exactly once per endpoint.
    /// v1 answers it automatically after the session/selection checks pass, which adds no
    /// displayed pairing code and no second user action.
    private var verificationHandlers: [String: (Bool) -> Void] = [:]

    /// Peers tracked at once, matching `MirorLinkCoordinator.MAX_TRACKED_PEERS`.
    ///
    /// Endpoint ids are peer-supplied and unauthenticated, so both dictionaries above
    /// grow on someone else's say-so. No real room has this many phones running Miror
    /// Link, and the shared coordinator ignores peers past the same cap anyway.
    private static let maxTrackedPeers = 32

    /// Mirror the shared wire budgets before converting native `Data` to a Kotlin
    /// `ByteArray`. Checking only after the bridge would allocate and copy an attacker-
    /// supplied oversized payload before Kotlin got a chance to reject it.
    private static let maxEndpointInfoBytes = 96
    private static let maxFrameBytes = 24 * 1024

    /// True when a new endpoint may be recorded in `map`. Existing keys always may, so an
    /// update to a peer already being tracked is never dropped.
    private func canTrack<Value>(_ map: [String: Value], _ endpointID: String) -> Bool {
        map[endpointID] != nil || map.count < Self.maxTrackedPeers
    }

    /// Answers an outstanding verification handler exactly once and forgets it.
    ///
    /// Nearby requires each handler to be invoked precisely once. Dropping one leaves the
    /// framework holding a half-open request; calling one twice is a contract violation.
    /// Every path that ends an endpoint's life goes through here so neither can happen by
    /// omission.
    @discardableResult
    private func resolveVerification(_ endpointID: String, _ accept: Bool) -> Bool {
        guard let handler = verificationHandlers.removeValue(forKey: endpointID) else {
            return false
        }
        handler(accept)
        return true
    }

    func isAvailable() -> Bool { true }

    func setListener(listener: MirorLinkNativeListener?) {
        self.listener = listener
    }

    private func manager(serviceId: String) -> ConnectionManager {
        if let existing = connectionManager { return existing }
        let created = ConnectionManager(serviceID: serviceId, strategy: .pointToPoint)
        created.delegate = self
        connectionManager = created
        return created
    }

    func startAdvertising(endpointInfo info: Data, serviceId: String) {
        let manager = self.manager(serviceId: serviceId)
        let created = Advertiser(connectionManager: manager)
        created.delegate = self
        created.startAdvertising(using: info)
        advertiser = created
    }

    func startDiscovery(serviceId: String) {
        let manager = self.manager(serviceId: serviceId)
        let created = Discoverer(connectionManager: manager)
        created.delegate = self
        created.startDiscovery()
        discoverer = created
    }

    func stopAdvertising() {
        advertiser?.stopAdvertising()
        advertiser = nil
    }

    func stopDiscovery() {
        discoverer?.stopDiscovery()
        discoverer = nil
    }

    func requestConnection(endpointId: String, endpointInfo info: Data) {
        discoverer?.requestConnection(to: endpointId, using: info)
    }

    func acceptConnection(endpointId: String) {
        resolveVerification(endpointId, true)
    }

    func rejectConnection(endpointId: String) {
        resolveVerification(endpointId, false)
    }

    func send(endpointId: String, frame: Data) {
        _ = connectionManager?.send(frame, to: [endpointId])
    }

    func disconnect(endpointId: String) {
        connectionManager?.disconnect(from: endpointId)
    }

    func shutdown() {
        stopAdvertising()
        stopDiscovery()
        // Answer every outstanding callback so Nearby never leaves a half-open request.
        for endpointID in verificationHandlers.keys {
            resolveVerification(endpointID, false)
        }
        verificationHandlers.removeAll()
        endpointInfo.removeAll()
        connectionManager = nil
    }
}

extension MirorLinkService: DiscovererDelegate {
    func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
        guard context.count <= Self.maxEndpointInfoBytes else { return }
        guard canTrack(endpointInfo, endpointID) else { return }
        endpointInfo[endpointID] = context
        listener?.onEndpointFound(endpointId: endpointID, endpointInfo: context.kotlinByteArray)
    }

    func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        endpointInfo.removeValue(forKey: endpointID)
        listener?.onEndpointLost(endpointId: endpointID)
    }
}

extension MirorLinkService: AdvertiserDelegate {
    func advertiser(
        _ advertiser: Advertiser,
        didReceiveConnectionRequestFrom endpointID: EndpointID,
        with context: Data,
        connectionRequestHandler: @escaping (Bool) -> Void
    ) {
        guard context.count <= Self.maxEndpointInfoBytes else {
            connectionRequestHandler(false)
            return
        }
        guard canTrack(endpointInfo, endpointID) else {
            connectionRequestHandler(false)
            return
        }
        endpointInfo[endpointID] = context
        // Accept the *request*; whether the connection completes is decided by the
        // verification handler below, after the coordinator's selection checks.
        connectionRequestHandler(true)
        listener?.onConnectionInitiated(
            endpointId: endpointID,
            endpointInfo: context.kotlinByteArray,
            incoming: true
        )
    }
}

extension MirorLinkService: ConnectionManagerDelegate {
    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive verificationCode: String,
        from endpointID: EndpointID,
        verificationHandler: @escaping (Bool) -> Void
    ) {
        // The code is deliberately not displayed: v1 has no manual pairing step.
        //
        // First handler wins. Storing the newcomer would drop the original on the floor
        // still unanswered, which is the one thing Nearby's exactly-once contract forbids;
        // refusing the duplicate keeps a single owner per endpoint and needs no reasoning
        // about which of two callbacks is live.
        guard verificationHandlers[endpointID] == nil else {
            verificationHandler(false)
            return
        }
        guard canTrack(verificationHandlers, endpointID) else {
            verificationHandler(false)
            return
        }
        verificationHandlers[endpointID] = verificationHandler
        listener?.onConnectionInitiated(
            endpointId: endpointID,
            endpointInfo: endpointInfo[endpointID]?.kotlinByteArray,
            incoming: false
        )
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive data: Data,
        withID payloadID: PayloadID,
        from endpointID: EndpointID
    ) {
        guard data.count <= Self.maxFrameBytes else { return }
        listener?.onFrameReceived(endpointId: endpointID, frame: data.kotlinByteArray)
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didChangeTo state: ConnectionState,
        for endpointID: EndpointID
    ) {
        switch state {
        case .connected:
            listener?.onConnected(endpointId: endpointID)
        case .disconnected:
            // Resolved rather than discarded: an outstanding handler dropped here would
            // leave Nearby waiting on an answer that never comes.
            resolveVerification(endpointID, false)
            endpointInfo.removeValue(forKey: endpointID)
            listener?.onDisconnected(endpointId: endpointID)
        case .rejected:
            resolveVerification(endpointID, false)
            endpointInfo.removeValue(forKey: endpointID)
            listener?.onConnectionFailed(endpointId: endpointID, reason: "rejected")
        default:
            break
        }
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive stream: InputStream,
        withID payloadID: PayloadID,
        from endpointID: EndpointID,
        cancellationToken token: CancellationToken
    ) {}

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didStartReceivingResourceWithID payloadID: PayloadID,
        from endpointID: EndpointID,
        at localURL: URL,
        withName name: String,
        cancellationToken token: CancellationToken
    ) {}

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceiveTransferUpdate update: TransferUpdate,
        from endpointID: EndpointID,
        forPayload payloadID: PayloadID
    ) {}
}

private extension Data {
    /// Kotlin/Native exposes `ByteArray` as `KotlinByteArray`; this is the one place the
    /// conversion happens.
    var kotlinByteArray: KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

#endif

/// Called during app startup. Registers the bridge only when the Nearby
/// package is actually present, so a build without it degrades to "Link unsupported"
/// rather than failing to compile or crashing at runtime.
@objc public final class MirorLinkBootstrap: NSObject {
    @objc public static func install() {
        #if canImport(NearbyConnections)
        MirorLinkBridgeRegistry.shared.bridge = MirorLinkService()
        #endif
    }
}
