# Miror Link

Miror Link lets two people nearby open a direct, temporary connection and browse each
other's card collections without copying a share code or sending collection data through
a Miror server.

Both people choose a nickname and open Link. When the nearby match is clear, Miror
connects automatically; when several people are nearby, it asks the user to choose.
Either person can then browse the other's read-only collection. Card photos can be
requested as cards are opened and can be disabled by either side.

## Privacy and connection model

- Link operates only while its screen is open and stops when the app leaves the
  foreground.
- It uses ephemeral session identifiers rather than accounts or persistent device IDs.
- Collections and card photos travel directly between the nearby devices.
- Miror does not treat collection contents or card photos as confidential, so Link does
  not add its own encryption, identity system, or pairing-code comparison on top of the
  nearby transport.
- Without an out-of-band identity check, a nearby device can impersonate another nearby
  participant. Link therefore does not use the connection itself as authority for any
  durable content.

## Relayed content updates

A device with newer card data may offer that update to its peer. The receiving user must
approve the transfer before it begins.

Relayed packages use the same content format as Miror's published releases. The catalog,
embedding gallery, ID manifest, and content version are covered by one official
signature. A relaying phone can forward that proof but cannot create or alter it.
Tampered, incomplete, mismatched, unsigned, implausibly versioned, or rollback packages
are rejected before the catalog is installed.

The verification keys are public by design and are included in both the application and
this source snapshot. Published content bundles can therefore be independently checked
without trusting the phone that relayed them.

## Resource and abuse controls

Link bounds advertisements, tracked peers, protocol frames, collection snapshots, photo
requests and assemblies, image dimensions, staged content, and transfer duration. Only
accepted protocol progress extends an active session; duplicate or replayed messages do
not. Discovery releases its radios after 90 seconds, and a completed exchange remains
available for up to 10 minutes of meaningful viewing activity.

Received photos are session-only, validated before decoding, and governed by a user
off-switch. Interrupted content staging and temporary received photos are reclaimed
without modifying the user's collection.

## Platform status

Android is the currently supported Miror Link platform.

The shared Kotlin and Swift-facing iOS implementation is included for review, but iOS
Link remains unavailable until its Nearby Connections dependency, generated Bonjour
service type, and native backpressure behavior have been integrated and verified on
Apple hardware.

## Reviewing the source

The feature-owned implementation is grouped under:

- `composeApp/src/commonMain/kotlin/com/selenite/scanner/link/`
- `composeApp/src/androidMain/kotlin/com/selenite/scanner/link/`
- `composeApp/src/iosMain/kotlin/com/selenite/scanner/link/`
- `iosApp/iosApp/MirorLink/`

The audit boundary also includes the share-visible collection-field mapper, compact
collection-manifest codec reached by Link, and the signed-content verifier, staging
rules, and installation gate reached by a relayed update. These are included because
they determine what leaves the device, parse peer-controlled bytes, or decide whether
those bytes may affect durable application state.

The audit boundary is the path described above. A broader Miror source distribution may
also contain host-app plumbing such as the collection screen, settings storage,
activity/bootstrap code, build configuration, or the catalog's transactional database
implementation. Those surrounding files are not required to inspect MLNK frame handling,
session behavior, resource bounds, collection decoding, image gating, content signature
verification, or the rule that verification precedes installation.

This directory is organized as an audit guide rather than a promise that the surrounding
repository is a standalone Miror Link module. It identifies the complete
security-sensitive path reached by a Link peer even when that path is published alongside
additional Miror source.

Internal test suites, debug probes, trained model weights, release credentials, local
configuration, and user/device data are not part of this source snapshot. Production
code defining and verifying the signed-content format and the public verification keys
is included.
