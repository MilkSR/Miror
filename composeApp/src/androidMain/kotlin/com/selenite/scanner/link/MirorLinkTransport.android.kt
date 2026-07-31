package com.selenite.scanner.link

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val mirorLinkTaskExecutor = Executor { command -> command.run() }

internal class MirorLinkPlatformTaskCancelled : Exception("Nearby operation was cancelled")

/**
 * Converts every Google Task terminal state into exactly one coroutine outcome.
 *
 * The explicit direct executor makes the bridge deterministic and avoids depending on an
 * Android main looper. Coroutine cancellation remains separate: it marks the continuation
 * inactive, and a later platform completion runs only the optional retirement cleanup.
 */
internal suspend fun awaitMirorLinkTask(
    task: Task<*>,
    onLateCompletion: ((Throwable?) -> Unit)? = null,
) {
    suspendCancellableCoroutine { continuation ->
        val cancellationCause = AtomicReference<Throwable?>(null)
        continuation.invokeOnCancellation { cause ->
            cancellationCause.compareAndSet(null, cause)
        }
        task.addOnCompleteListener(mirorLinkTaskExecutor) { completed ->
            if (!continuation.isActive) {
                runCatching { onLateCompletion?.invoke(cancellationCause.get()) }
                return@addOnCompleteListener
            }
            when {
                completed.isSuccessful -> continuation.resume(Unit)
                completed.isCanceled ->
                    continuation.resumeWithException(MirorLinkPlatformTaskCancelled())
                else -> continuation.resumeWithException(
                    completed.exception
                        ?: IllegalStateException("Nearby operation completed without a result"),
                )
            }
        }
    }
}

/**
 * Runtime permissions Nearby Connections needs, by OS version.
 *
 * Both branches are live for Miror: `minSdk` is 26, so pre-31 devices genuinely need
 * the legacy Bluetooth plus fine-location set, while 31+ uses the split Bluetooth
 * permissions and 33+ replaces location with `NEARBY_WIFI_DEVICES`.
 */
object MirorLinkPermissions {
    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // Location is required on **every** API level Miror supports, not just the legacy
        // branch. Measured on a Pixel 9 (API 37): startDiscovery rejects with
        // MISSING_PERMISSION_ACCESS_COARSE_LOCATION, then with
        // MISSING_PERMISSION_ACCESS_FINE_LOCATION, until both are held.
        //
        // This is the direct cost of not asserting neverForLocation on BLUETOOTH_SCAN,
        // which the plan rules out because Link's ambiguity ranking really does use BLE
        // RSSI to infer physical closeness. Both are requested together because Android 12+
        // rejects a fine-only request.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }.distinct()

    fun allGranted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Nearby Connections transport.
 *
 * Application-scoped: it is constructed with an application context and holds no
 * `Activity`, so the configuration changes that recreate `MainActivity` -- including
 * the automatic theme change at sunset that `ContentUpdateManager` documents -- cannot
 * leave a second client driving the radios.
 */
internal class AndroidMirorLinkTransport(
    context: Context,
) : MirorLinkTransport {

    private val appContext = context.applicationContext
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(appContext) }

    private val _events = MutableSharedFlow<MirorLinkTransportEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<MirorLinkTransportEvent> = _events.asSharedFlow()

    /**
     * Bounded in-flight payloads. Without this a content relay would hand Nearby
     * thousands of queued chunks at once and hold the whole package in memory, which is
     * exactly what the plan forbids.
     */
    private val inFlight = Semaphore(MAX_IN_FLIGHT_PAYLOADS)
    private val outstandingPayloads = ConcurrentHashMap<Long, Boolean>()

    @Volatile
    private var advertising = false

    @Volatile
    private var discovering = false

    private fun emitBlocking(event: MirorLinkTransportEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "Dropped a Link transport event; the consumer is not keeping up")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            // Oversized frames are rejected by the decoder, but refusing them here keeps a
            // hostile peer from ever handing the parser something outside the budget.
            if (bytes.size > MirorLinkProtocol.MAX_FRAME_BYTES) {
                Log.w(TAG, "Discarding an oversized Link frame of ${bytes.size} bytes")
                return
            }
            emitBlocking(MirorLinkTransportEvent.FrameReceived(endpointId, bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS,
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED,
                -> releasePayload(update.payloadId)
            }
        }
    }

    private fun releasePayload(payloadId: Long) {
        if (outstandingPayloads.remove(payloadId) != null) {
            runCatching { inFlight.release() }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            emitBlocking(
                MirorLinkTransportEvent.ConnectionInitiated(
                    endpointId = endpointId,
                    info = MirorLinkEndpointInfo.decode(info.endpointInfo),
                    isIncoming = info.isIncomingConnection,
                ),
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK ->
                    emitBlocking(MirorLinkTransportEvent.Connected(endpointId))

                else -> emitBlocking(
                    MirorLinkTransportEvent.ConnectionFailed(
                        endpointId,
                        resolution.status.statusMessage ?: "connection rejected",
                    ),
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            emitBlocking(MirorLinkTransportEvent.Disconnected(endpointId))
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // A non-Miror advertisement on the same service id decodes to null and is
            // simply not a candidate; it must not abort the discovery pass.
            val decoded = MirorLinkEndpointInfo.decode(info.endpointInfo) ?: return
            emitBlocking(MirorLinkTransportEvent.PeerFound(endpointId, decoded))
        }

        override fun onEndpointLost(endpointId: String) {
            emitBlocking(MirorLinkTransportEvent.PeerLost(endpointId))
        }
    }

    override suspend fun availability(): MirorLinkAvailability {
        val playServices = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext)
        if (playServices != ConnectionResult.SUCCESS) return MirorLinkAvailability.Unsupported
        if (!MirorLinkPermissions.allGranted(appContext)) {
            return MirorLinkAvailability.PermissionsRequired
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter == null) return MirorLinkAvailability.Unsupported
        if (!adapter.isEnabled) return MirorLinkAvailability.RadiosOff
        return MirorLinkAvailability.Available
    }

    override suspend fun startAdvertising(endpointInfo: ByteArray) {
        if (advertising) return
        advertising = true
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        try {
            awaitTask(
                name = "startAdvertising",
                failureIsFatal = true,
                cleanLateStartup = true,
            ) {
                client.startAdvertising(endpointInfo, SERVICE_ID, connectionCallback, options)
            }
        } catch (failure: Throwable) {
            advertising = false
            throw failure
        }
    }

    override suspend fun startDiscovery() {
        if (discovering) return
        discovering = true
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        // Fatal on failure: a discovery that never starts is indistinguishable from "no
        // one is nearby", so silently swallowing it leaves the user staring at "Looking
        // nearby" forever with no way to know something is actually wrong.
        try {
            awaitTask(
                name = "startDiscovery",
                failureIsFatal = true,
                cleanLateStartup = true,
            ) {
                client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            }
        } catch (failure: Throwable) {
            discovering = false
            throw failure
        }
    }

    override suspend fun stopDiscovery() {
        discovering = false
        // Deliberately unconditional. A pending start task can cross an earlier stop:
        // its optimistic flag was already cleared, but the platform operation may still
        // complete afterwards. Repeating Nearby's idempotent stop closes that race.
        runCatching { client.stopDiscovery() }
    }

    override suspend fun stopAdvertising() {
        advertising = false
        // Same late-start rule as discovery; callers may safely reconcile more than once.
        runCatching { client.stopAdvertising() }
    }

    override suspend fun requestConnection(endpointId: String, endpointInfo: ByteArray) {
        awaitTask("requestConnection") {
            client.requestConnection(endpointInfo, endpointId, connectionCallback)
        }
    }

    override suspend fun acceptConnection(endpointId: String) {
        // Nearby requires this callback to be answered on both endpoints before a
        // connection completes. Answering it automatically after the session/selection
        // checks is not a displayed pairing code or a second user action.
        awaitTask("acceptConnection") { client.acceptConnection(endpointId, payloadCallback) }
    }

    override suspend fun rejectConnection(endpointId: String) {
        runCatching { client.rejectConnection(endpointId) }
    }

    override suspend fun send(endpointId: String, frame: ByteArray) {
        require(frame.size <= MirorLinkProtocol.MAX_FRAME_BYTES) {
            "Link frame exceeds the transport budget"
        }
        inFlight.acquire()
        val payload = Payload.fromBytes(frame)
        outstandingPayloads[payload.id] = true
        try {
            awaitTask("sendPayload") { client.sendPayload(endpointId, payload) }
        } catch (failure: Throwable) {
            releasePayload(payload.id)
            throw failure
        }
    }

    override suspend fun disconnect(endpointId: String) {
        runCatching { client.disconnectFromEndpoint(endpointId) }
    }

    override suspend fun shutdown() {
        advertising = false
        discovering = false
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopAllEndpoints() }
        outstandingPayloads.keys.toList().forEach { releasePayload(it) }
    }

    private suspend fun <T> awaitTask(
        name: String,
        failureIsFatal: Boolean = false,
        cleanLateStartup: Boolean = false,
        block: () -> com.google.android.gms.tasks.Task<T>,
    ) {
        val task = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Exception messages can contain Nearby endpoint identifiers. The coordinator
            // maps failures to fixed user-facing copy.
            Log.w(TAG, "$name threw before dispatch")
            if (failureIsFatal) {
                emitBlocking(
                    MirorLinkTransportEvent.TransportFailure(
                        failure.message ?: name,
                        fatal = true,
                    ),
                )
            }
            throw failure
        }

        try {
            awaitMirorLinkTask(task) { cancellationCause ->
                if (cleanLateStartup && cancellationCause is TimeoutCancellationException) {
                    /*
                     * Only the coordinator's hard startup deadline poisons further starts
                     * for this process. A sibling advertising/discovery failure also
                     * cancels this await, but does not poison replacement sessions; cleaning
                     * that late completion with stopAllEndpoints() could kill a healthy
                     * replacement. The captured cancellation cause makes that ownership
                     * decision explicit instead of inferring it from continuation.isActive.
                     */
                    advertising = false
                    discovering = false
                    client.stopAllEndpoints()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A failed Nearby call is normally reported as a non-fatal event: an
            // already-connected endpoint or duplicate advertise is churn, not a session
            // failure. Starting the radios is the exception -- if that fails, nothing
            // useful can happen.
            Log.w(TAG, "$name failed")
            val alreadyRunning = (failure as? ApiException)?.statusCode?.let {
                it == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING ||
                    it == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
            } ?: false
            if (alreadyRunning) return
            emitBlocking(
                MirorLinkTransportEvent.TransportFailure(
                    failure.message ?: name,
                    fatal = failureIsFatal,
                ),
            )
            throw failure
        }
    }

    companion object {
        private const val TAG = "MirorLinkTransport"

        /**
         * Stable for the life of the feature and deliberately free of any app or protocol
         * version. Protocol evolution happens inside HELLO, so a future Miror still
         * discovers an older one.
         */
        const val SERVICE_ID = "com.selenite.scanner.mirorlink"

        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
        private const val MAX_IN_FLIGHT_PAYLOADS = 8
    }
}
