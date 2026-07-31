"""
Detached signatures for Miror content releases.

A content release is three files that must move together: ``cards.db``,
``master_index_v3.bin`` and ``master_index_v3_ids.txt``. Miror Link relays those files
between phones over an untrusted radio, so the receiver cannot trust the sender to tell
the truth about them. The signature is what lets a device check, offline and unaided,
that a package came from this pipeline regardless of which peer handed it over.

Design notes that are load-bearing rather than stylistic:

*One signature over all three digests.*  Per-file signatures would allow mix-and-match --
v12's genuine ``cards.db`` beside v13's genuine ``.bin`` and v13's genuine signature,
every part authentic. Binding all three into a single signed message makes that
combination unsigned. This is exactly the torn state ``ContentUpdateManager`` warns about
(a catalog naming cards the gallery cannot scan), now structurally impossible.

*The version is inside the message.*  Otherwise a peer can relabel a genuine v12 package
as v2147483647, and because every version check in the app is relative ("newer than
mine") and permanent, that would pin the device off updates forever.

*The signature lives in the package, appended last.*  It has to travel inside the files so
a device can relay content it received without being able to forge it -- a signature
carried in the wire protocol would die after one relay hop. It goes in the ids file
because that is the only one of the three that can be read safely before verification:
``cards.db`` would mean opening SQLite (the parse being gated) and the ``.bin`` length is
checked exactly against the row count. And it is *appended*, never prepended, because
``printlens_v3_families.tsv`` addresses the global gallery by row position -- shifting
those positions would make PrintLens rerank against the wrong card, silently.

ECDSA P-256 rather than Ed25519: Miror's ``minSdk`` is 26, and Ed25519 only reached the
Android platform JCA in API 33. ``SHA256withECDSA`` has been available since API 1.
"""

from __future__ import annotations

import base64
import hashlib
import struct
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

CARDS_DB = "cards.db"
INDEX_BIN = "master_index_v3.bin"
INDEX_IDS = "master_index_v3_ids.txt"
RELEASE_FILES = (CARDS_DB, INDEX_BIN, INDEX_IDS)

SIGNATURE_PREFIX = "#miror-sig-"
SIGNATURE_TAG = "miror-sig-v1"

# Domain separation: a signature produced for any other purpose must never be
# reinterpretable as a content signature.
DOMAIN = b"miror.content.v1\x00"


class ContentSignatureError(Exception):
    """Raised for any malformed, missing or invalid release signature."""


# --------------------------------------------------------------------------- ids parsing


def is_id_line(line: str) -> bool:
    """True for a line denoting an actual gallery row (not blank, not metadata)."""
    stripped = line.strip()
    return bool(stripped) and not stripped.startswith("#")


def parse_ids(text: str) -> list[str]:
    """Gallery IDs in file order, with blank lines and metadata removed.

    Mirrors ``GalleryIds.parse`` on the Kotlin side. The two must agree exactly: the row
    count derived here sizes the ``.bin`` byte-count assertion, and the row count derived
    there sizes the runtime fp16 gallery.
    """
    return [line.strip() for line in text.splitlines() if is_id_line(line)]


def split_signature(raw: bytes) -> tuple[bytes, str | None]:
    """Split ids-file bytes into (body, signature_line).

    ``body`` is everything up to and including the newline that precedes the signature
    line, and is exactly what gets hashed. Only the final line is considered, so a
    signature placed anywhere else is not a signature -- position is part of the contract,
    both to keep the hashed input canonical and to guarantee gallery row positions never
    shift.
    """
    if not raw.endswith(b"\n"):
        raise ContentSignatureError("gallery ids must end with a newline")
    without_final_newline = raw[:-1]
    last_line_start = without_final_newline.rfind(b"\n") + 1
    try:
        last_line = without_final_newline[last_line_start:].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ContentSignatureError("gallery ids are not valid UTF-8") from exc

    if last_line.strip().startswith(SIGNATURE_PREFIX):
        body = raw[:last_line_start]
        assert_no_signature_in_body(body)
        return body, last_line.strip()
    assert_no_signature_in_body(raw)
    return raw, None


def assert_no_signature_in_body(body: bytes) -> None:
    """A signed release carries exactly one signature line, and it is the last one.

    A second marker is not forgeable -- the body is covered by the signature, so any
    marker inside it was authored by this pipeline. It is still refused, because "the
    final line is the signature" and "there is one signature" are different claims, and
    tools that reasonably assume the second would disagree about which line is
    authoritative. Canonical inputs are what make signature schemes debuggable.
    """
    for number, line in enumerate(body.split(b"\n"), start=1):
        if line.strip().startswith(SIGNATURE_PREFIX.encode("ascii")):
            raise ContentSignatureError(
                f"gallery ids carry a second signature marker at line {number}"
            )


# ------------------------------------------------------------------------------ hashing


def sha256_file(path: Path) -> bytes:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.digest()


def ids_body_bytes(path: Path) -> bytes:
    """The gallery rows of an ids file, without any trailing signature line."""
    body, _ = split_signature(Path(path).read_bytes())
    return body


def sha256_ids_body_hex(path: Path) -> str:
    """Hex digest of the ids **body** -- the value recorded and compared everywhere.

    Not the whole-file digest, and the distinction is load-bearing. ``cards.db`` carries
    ``index_ids_sha256`` in its ``content_meta``, and it has to be stamped *before* the
    release is signed, because the database's own digest is one of the three inside the
    signature. Stamping afterwards would invalidate the thing being stamped.

    So the only value that can be both recorded at build time and re-derived at verify
    time is the body digest. It is also the semantically right one: this hash identifies
    the gallery content, while the signature covers the envelope around it.

    For an unsigned file this equals the whole-file digest, so existing baselines and
    manifests keep verifying unchanged.
    """
    return hashlib.sha256(ids_body_bytes(path)).hexdigest()


def build_message(version: int, db_digest: bytes, bin_digest: bytes, ids_digest: bytes) -> bytes:
    """The exact byte string that gets signed and verified.

    Any change here is a wire-format break and must be matched byte-for-byte by
    ``ContentSignature.kt``. The round-trip test exists to catch drift.
    """
    if version <= 0:
        raise ContentSignatureError(f"content version must be positive, got {version}")
    for name, digest in (("cards.db", db_digest), ("index bin", bin_digest), ("ids", ids_digest)):
        if len(digest) != 32:
            raise ContentSignatureError(f"{name} digest must be 32 bytes, got {len(digest)}")
    return DOMAIN + struct.pack(">I", version) + db_digest + bin_digest + ids_digest


def release_message(release_dir: Path, version: int) -> bytes:
    """Builds the signed message for a release directory, ignoring any existing signature."""
    directory = Path(release_dir)
    for name in RELEASE_FILES:
        if not (directory / name).is_file():
            raise ContentSignatureError(f"release is missing {name}")
    body, _ = split_signature((directory / INDEX_IDS).read_bytes())
    return build_message(
        version,
        sha256_file(directory / CARDS_DB),
        sha256_file(directory / INDEX_BIN),
        hashlib.sha256(body).digest(),
    )


# ------------------------------------------------------------------------------- keys


def load_private_key(pem: bytes | str) -> ec.EllipticCurvePrivateKey:
    data = pem.encode("utf-8") if isinstance(pem, str) else pem
    key = serialization.load_pem_private_key(data, password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise ContentSignatureError("signing key is not an elliptic-curve private key")
    if not isinstance(key.curve, ec.SECP256R1):
        raise ContentSignatureError(f"signing key must be P-256, got {key.curve.name}")
    return key


def load_public_key(pem: bytes | str) -> ec.EllipticCurvePublicKey:
    data = pem.encode("utf-8") if isinstance(pem, str) else pem
    key = serialization.load_pem_public_key(data)
    if not isinstance(key, ec.EllipticCurvePublicKey):
        raise ContentSignatureError("verification key is not an elliptic-curve public key")
    return key


def public_key_der_base64(key: ec.EllipticCurvePublicKey) -> str:
    """X.509 SubjectPublicKeyInfo, base64. This is the form the app embeds."""
    return base64.b64encode(
        key.public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")


def generate_keypair() -> tuple[str, str]:
    """Returns (private_pem, public_pem). Used once; the private key is never regenerated."""
    private = ec.generate_private_key(ec.SECP256R1())
    private_pem = private.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode("ascii")
    public_pem = private.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")
    return private_pem, public_pem


# -------------------------------------------------------------------- sign and verify


def format_signature_line(key_id: str, signature: bytes) -> str:
    if not key_id or any(character.isspace() for character in key_id):
        raise ContentSignatureError("key id must be non-empty and contain no whitespace")
    return f"#{SIGNATURE_TAG} {key_id} {base64.b64encode(signature).decode('ascii')}"


def parse_signature_line(line: str) -> tuple[str, bytes]:
    parts = line.strip().split()
    if len(parts) != 3:
        raise ContentSignatureError(f"malformed signature line: {line!r}")
    tag, key_id, encoded = parts
    if tag != f"#{SIGNATURE_TAG}":
        raise ContentSignatureError(f"unsupported signature tag: {tag}")
    try:
        signature = base64.b64decode(encoded, validate=True)
    except Exception as exc:  # noqa: BLE001 - any decode failure is the same outcome
        raise ContentSignatureError("signature is not valid base64") from exc
    return key_id, signature


def sign_release(release_dir: Path, version: int, private_pem: bytes | str, key_id: str) -> str:
    """Signs a release in place, appending the signature line to the ids file.

    Re-signing an already-signed release replaces the existing line rather than stacking
    a second one, so this is safe to re-run.
    """
    directory = Path(release_dir)
    ids_path = directory / INDEX_IDS
    body, _ = split_signature(ids_path.read_bytes())

    message = build_message(
        version,
        sha256_file(directory / CARDS_DB),
        sha256_file(directory / INDEX_BIN),
        hashlib.sha256(body).digest(),
    )
    signature = load_private_key(private_pem).sign(message, ec.ECDSA(hashes.SHA256()))
    line = format_signature_line(key_id, signature)

    # Written body-first so a crash mid-write cannot leave a file whose signature does
    # not describe the bytes above it.
    ids_path.write_bytes(body + line.encode("ascii") + b"\n")
    return line


def verify_release(release_dir: Path, version: int, public_keys: dict[str, str]) -> str:
    """Verifies a signed release. Returns the key id used, or raises.

    ``public_keys`` maps key id to PEM. Multiple entries are supported so a key can be
    rotated: ship the new key in the app first, then start signing with it.
    """
    directory = Path(release_dir)
    for name in RELEASE_FILES:
        if not (directory / name).is_file():
            raise ContentSignatureError(f"release is missing {name}")

    body, signature_line = split_signature((directory / INDEX_IDS).read_bytes())
    if signature_line is None:
        raise ContentSignatureError("release carries no signature line")

    key_id, signature = parse_signature_line(signature_line)
    pem = public_keys.get(key_id)
    if pem is None:
        raise ContentSignatureError(f"signature uses unknown key id {key_id!r}")

    message = build_message(
        version,
        sha256_file(directory / CARDS_DB),
        sha256_file(directory / INDEX_BIN),
        hashlib.sha256(body).digest(),
    )
    try:
        load_public_key(pem).verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as exc:
        raise ContentSignatureError(
            "signature does not match these files at this version"
        ) from exc
    return key_id
