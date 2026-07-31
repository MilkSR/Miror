#!/usr/bin/env python3
"""
Keeps the iOS Link configuration honest and in step with the shared service id.

Two things have already gone wrong here and both were invisible from a Windows checkout:
the Bonjour service type was written as the literal service id (Nearby derives it from a
SHA-256 instead), and a `_udp` type was declared that Nearby never registers. Neither
would fail a build; they would simply mean iOS never discovers anyone.

So this asserts the shape rather than trusting the file:

  * the service id is read from the Android transport, which is the single definition,
  * `NSBonjourServices` may be absent -- iOS Link is not wired up yet -- but if present it
    must be exactly one `_<hex>._tcp` entry derived from that id,
  * the literal-service-id and `_udp` forms are refused by name, because those are the
    specific mistakes that were made.

The exact byte-to-string encoding Google uses is **not** pinned here. Their Get Started
page documents "the first 12 bytes of the SHA-256 hash of your app's service ID" and
supplies a generator, but does not state the encoding, and it could not be confirmed
without a Mac. Rather than bake in a guess, `EXPECTED_BONJOUR_TYPE` stays None and this
script refuses anything that is obviously wrong while declining to bless anything as
right. Fill it in from the generator, and from then on drift is a test failure.

See docs/miror-link/MIROR_LINK_IOS_HANDOFF.md.
"""

from __future__ import annotations

import hashlib
import plistlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
INFO_PLIST = REPO / "iosApp" / "iosApp" / "Info.plist"
ANDROID_TRANSPORT = (
    REPO
    / "composeApp/src/androidMain/kotlin/com/selenite/scanner/link/MirorLinkTransport.android.kt"
)
XCODE_PROJECT = REPO / "iosApp" / "iosApp.xcodeproj" / "project.pbxproj"

# Set this once the value has been produced by Google's generator on a Mac. Until then
# the checks below still refuse the known-wrong forms.
EXPECTED_BONJOUR_TYPE: str | None = None

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)
    print(f"  BAD  {message}")


def ok(message: str) -> None:
    print(f"  OK   {message}")


def note(message: str) -> None:
    notes.append(message)
    print(f"  NOTE {message}")


def shared_service_id() -> str:
    """The one definition of the Link service id, read from the Android transport."""
    text = ANDROID_TRANSPORT.read_text(encoding="utf-8")
    match = re.search(r'const val SERVICE_ID = "([^"]+)"', text)
    if not match:
        raise SystemExit("could not read SERVICE_ID from the Android transport")
    return match.group(1)


def sha256_prefix_hex(service_id: str, byte_count: int) -> str:
    return hashlib.sha256(service_id.encode("utf-8")).hexdigest()[: byte_count * 2]


def main() -> int:
    service_id = shared_service_id()
    print(f"Miror Link service id: {service_id}\n")

    plist = plistlib.loads(INFO_PLIST.read_bytes())

    # Local network access is required whether or not Bonjour is declared yet.
    if plist.get("NSLocalNetworkUsageDescription"):
        ok("NSLocalNetworkUsageDescription present")
    else:
        fail("NSLocalNetworkUsageDescription is missing")

    if plist.get("NSBluetoothAlwaysUsageDescription"):
        ok("NSBluetoothAlwaysUsageDescription present")
    else:
        fail("NSBluetoothAlwaysUsageDescription is missing")

    # Whether the dependency is wired decides how strict everything below is. While it
    # is absent iOS Link cannot run at all, so an unfinished plist is merely unfinished.
    # The moment Nearby is added the app really does try to discover over the local
    # network, and Google requires the generated _tcp type to be declared -- so from then
    # on an absent or unpinned type is a shipping defect, not a to-do.
    nearby_wired = XCODE_PROJECT.is_file() and "NearbyConnections" in XCODE_PROJECT.read_text(
        encoding="utf-8", errors="replace"
    )

    services = plist.get("NSBonjourServices")
    if services is None:
        if nearby_wired:
            fail(
                "NearbyConnections is wired but NSBonjourServices is absent: iOS cannot "
                "discover peers without the generated _tcp service type"
            )
        else:
            note(
                "NSBonjourServices absent -- correct while iOS Link is unwired; populate "
                "it from Google's generator when the SPM package lands"
            )
    else:
        if not isinstance(services, list) or not services:
            fail("NSBonjourServices must be a non-empty array when present")
            services = []
        for entry in services:
            if service_id in entry:
                fail(
                    f"{entry!r} embeds the service id literally; Nearby derives the type "
                    "from a SHA-256 of it"
                )
            if entry.endswith("._udp"):
                fail(f"{entry!r} declares a _udp type, which Nearby never registers")
            if not re.fullmatch(r"_[0-9a-zA-Z]{1,15}\._tcp", entry):
                fail(f"{entry!r} is not a valid DNS-SD service type of the form _<name>._tcp")
        if len(services) > 1:
            fail(f"expected exactly one service type, found {len(services)}")
        if EXPECTED_BONJOUR_TYPE is not None:
            if services == [EXPECTED_BONJOUR_TYPE]:
                ok(f"NSBonjourServices matches the derived type {EXPECTED_BONJOUR_TYPE}")
            else:
                fail(
                    f"NSBonjourServices is {services!r}, expected [{EXPECTED_BONJOUR_TYPE!r}] "
                    f"for service id {service_id!r}"
                )
        elif services:
            if nearby_wired:
                fail(
                    "EXPECTED_BONJOUR_TYPE is unset, so the declared service type cannot "
                    "be checked against the service id; pin it now that Nearby is wired"
                )
            else:
                note(
                    "a service type is declared but EXPECTED_BONJOUR_TYPE is unset, so it "
                    "cannot be checked against the service id -- fill it in"
                )

    if nearby_wired:
        ok("NearbyConnections is referenced by the Xcode project")
    else:
        note(
            "NearbyConnections is NOT in the Xcode project: iOS Link is unavailable at "
            "runtime, not merely unverified"
        )

    # Reference material for whoever runs the generator.
    print("\nSHA-256 of the service id (for cross-checking the generator's output):")
    print(f"  full   : {hashlib.sha256(service_id.encode()).hexdigest()}")
    for count in (3, 6, 12):
        print(f"  first {count:2d} bytes, hex: {sha256_prefix_hex(service_id, count)}")

    print()
    if failures:
        print("iOS LINK CONFIG NOT SAFE TO SHIP -- fix the failures above.")
        return 1
    print("iOS Link configuration consistent.")
    if notes:
        print(f"({len(notes)} note(s) above are expected while iOS Link is unwired.)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
