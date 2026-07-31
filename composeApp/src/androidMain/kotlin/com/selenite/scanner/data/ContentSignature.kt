package com.selenite.scanner.data

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies that a content release was produced by Miror's release pipeline.
 *
 * Miror Link relays content packages phone to phone over an untrusted radio, and the
 * digests carried in the wire protocol are supplied by the sender -- they prove the bytes
 * arrived intact, never that the sender is entitled to author them. Without this check a
 * modified client could hand a peer any three files that merely *look* structurally
 * valid, and they would be merged into the live catalog permanently.
 *
 * Verification runs **before** `cards.db` is handed to SQLite. Hashing a file is only
 * reading bytes -- nothing is parsed, decoded, or opened as a database -- so a package
 * that fails this check never reaches a parser that could be attacked by its contents.
 *
 * ECDSA P-256 rather than Ed25519 because `minSdk` is 26 and Ed25519 only reached the
 * platform JCA in API 33. `SHA256withECDSA` has been present since API 1.
 *
 * The signed message must match `scripts/ContentUpdate/content_signature.py` byte for
 * byte. Nothing in either codebase would fail if the two drifted -- releases would simply
 * stop verifying on every device at once, after shipping. The only thing that turns that
 * into a test failure first is the pinned fixture in
 * `androidUnitTest/.../data/ContentSignatureTest.kt` (`Fixture`, exercised by
 * `aPackageSignedByTheReleasePipelineVerifies`), whose signature line was produced by the
 * Python signer and is checked here by the Kotlin verifier.
 */
object ContentSignature {

    private const val TAG = "ContentSignature"

    /**
     * Domain separator: a signature made for anything else must not verify here.
     *
     * The trailing NUL terminates the label so no future prefix can collide with it.
     * It is appended rather than written inline: a raw NUL inside a source literal
     * makes the file binary to git and grep. Must match DOMAIN in content_signature.py.
     */
    private val DOMAIN =
        "miror.content.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private const val SIGNATURE_TAG = "#miror-sig-v1"

    private val NEWLINE = '\n'.code.toByte()

    /**
     * How far back from the end of the ids file the signature line may start.
     *
     * A real line is a tag, a short key id and a base64 P-256 signature -- under 130
     * bytes. The window is generous enough to be future-proof and small enough that a
     * hostile file cannot make this allocation matter.
     */
    private const val MAX_SIGNATURE_TAIL_BYTES = 4096
    private const val MAX_SIGNATURE_LINE_BYTES = 512

    /** `#miror-sig-`, the version-agnostic prefix, as bytes for the streaming scan. */
    private val SIGNATURE_MARKER = "#miror-sig-".toByteArray(Charsets.US_ASCII)
    private const val ALGORITHM = "SHA256withECDSA"
    private const val KEY_ALGORITHM = "EC"

    /**
     * Accepted verification keys, by id.
     *
     * These are public keys; they ship in the APK, are extractable from it, and are
     * meant to be public. Private signing material is not part of the source-available
     * publication.
     *
     * More than one key is accepted so a key can be rotated: a new key must ship here
     * **before** the pipeline starts signing with it, or receivers on older builds would
     * silently stop accepting content. `e37bb53f` is that reserve and has never signed
     * anything.
     */
    private val ACCEPTED_KEYS: Map<String, String> = mapOf(
        "0140a43a" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOocivjh2kxNix2msUUa+WMy4jlK5" +
            "kh3h/hSF+pgTE6lWhfZ4ezHTta8Cb/c+6V4pG2ASXfC4B/83X+ZgE6dGUA==",
        "e37bb53f" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+Sp6mqrcInb34ABINbMCwrVx+94w" +
            "nFZYWtUI6uijOjFKJbtzfLCHf+FGV0evv3maf3ETi/youJ45KkkNA2Mpxw==",
    )

    /**
     * True when [dir] holds a complete release whose signature covers exactly these
     * bytes at exactly [expectedVersion].
     *
     * Any failure -- missing file, absent signature, unknown key, tampered byte, wrong
     * version -- returns false. There is deliberately no "unsigned is acceptable" branch:
     * no unsigned package has ever shipped, so allowing one would create a permanently
     * weaker path for no compatibility benefit.
     */
    fun verify(dir: File, expectedVersion: Int): Boolean {
        return try {
            verifyOrThrow(dir, expectedVersion)
            true
        } catch (failure: Throwable) {
            Log.w(TAG, "Content signature rejected: ${failure.message}")
            false
        }
    }

    private fun verifyOrThrow(dir: File, expectedVersion: Int) {
        require(expectedVersion > 0) { "content version must be positive" }
        ContentAssets.REQUIRED_FILES.forEach { name ->
            require(File(dir, name).isFile) { "release is missing $name" }
        }

        val idsFile = File(dir, ContentAssets.INDEX_IDS)
        val located = locateSignature(idsFile)
        val parsed = parseSignatureLine(located.line)
        val encodedKey = ACCEPTED_KEYS[parsed.keyId]
            ?: error("signature uses unknown key id ${parsed.keyId}")

        val message = buildMessage(
            version = expectedVersion,
            cardsDigest = digestOf(File(dir, ContentAssets.CARDS_DB)),
            indexDigest = digestOf(File(dir, ContentAssets.INDEX_BIN)),
            idsDigest = digestOfPrefix(idsFile, located.bodyLength),
        )

        val publicKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)),
        )
        val verified = Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey)
            update(message)
            verify(parsed.signature)
        }
        if (!verified) error("signature does not match these files at v$expectedVersion")
        Log.i(TAG, "Content signature verified for v$expectedVersion with key ${parsed.keyId}")
    }

    /** Where the signed body ends, and the signature line that follows it. */
    internal class LocatedSignature(val bodyLength: Long, val line: String)

    /**
     * Finds the trailing signature by reading only the tail of the file.
     *
     * Deliberately never loads the ids file whole. The protocol permits a content file
     * of up to [MirorLinkProtocol.MAX_CONTENT_FILE_BYTES], and a peer whose package the
     * user has consented to receive controls every byte of it. Reading it into a single
     * array -- then decoding it to UTF-16, splitting it, and Base64-decoding a chunk of
     * it -- turns a bounded disk cost into a predictable heap exhaustion, which on a
     * Pixel XL-class device is a crash rather than a rejection.
     *
     * Consent covers spending disk on a package. It does not cover being killed by it.
     */
    internal fun locateSignature(file: File): LocatedSignature {
        val length = file.length()
        require(length > 0) { "gallery ids are empty" }
        val tailSize = minOf(length, MAX_SIGNATURE_TAIL_BYTES.toLong()).toInt()
        val tail = ByteArray(tailSize)
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(length - tailSize)
            handle.readFully(tail)
        }
        require(tail[tail.size - 1] == NEWLINE) { "gallery ids must end with a newline" }

        // Index of the newline that terminates the line before the last one.
        var separator = -1
        for (index in tail.size - 2 downTo 0) {
            if (tail[index] == NEWLINE) {
                separator = index
                break
            }
        }
        if (separator < 0) {
            // The final line is longer than the tail we are willing to read, so it cannot
            // be a signature line -- a real one is well under a hundred bytes.
            error("no signature line within the last $MAX_SIGNATURE_TAIL_BYTES bytes")
        }
        val lineBytes = tail.copyOfRange(separator + 1, tail.size - 1)
        require(lineBytes.size <= MAX_SIGNATURE_LINE_BYTES) { "signature line is too long" }
        val line = String(lineBytes, Charsets.UTF_8).trim()
        require(line.startsWith(SIGNATURE_TAG)) { "release carries no signature line" }

        // Body length in absolute file terms: everything up to and including the newline
        // that precedes the signature line.
        return LocatedSignature(
            bodyLength = length - tailSize + separator + 1,
            line = line,
        )
    }

    /**
     * The exact bytes that are signed.
     *
     * All three digests live in one message on purpose. Per-file signatures would allow
     * mix-and-match -- one release's genuine `cards.db` beside another's genuine gallery
     * and genuine signature, every part authentic -- which is precisely the torn state
     * that yields cards the catalog lists but the scanner cannot find.
     *
     * The version is inside the message for the same reason: it stops a peer relabelling
     * a genuine old package as a far newer one, which would otherwise pin the device off
     * updates permanently.
     */
    internal fun buildMessage(
        version: Int,
        cardsDigest: ByteArray,
        indexDigest: ByteArray,
        idsDigest: ByteArray,
    ): ByteArray {
        require(cardsDigest.size == 32 && indexDigest.size == 32 && idsDigest.size == 32) {
            "content digests must be 32 bytes"
        }
        val message = ByteArray(DOMAIN.size + 4 + 96)
        var offset = 0
        DOMAIN.copyInto(message, offset); offset += DOMAIN.size
        // Big-endian, matching struct.pack(">I", version) on the Python side.
        message[offset++] = (version ushr 24).toByte()
        message[offset++] = (version ushr 16).toByte()
        message[offset++] = (version ushr 8).toByte()
        message[offset++] = version.toByte()
        cardsDigest.copyInto(message, offset); offset += 32
        indexDigest.copyInto(message, offset); offset += 32
        idsDigest.copyInto(message, offset)
        return message
    }

    internal class SplitIds(val body: ByteArray, val signatureLine: String?)

    /**
     * Splits the ids file into the signed body and the trailing signature line.
     *
     * Only the final line counts. Position is part of the contract, for two reasons: the
     * hashed input has to be canonical, and the signature must never displace a gallery
     * row -- `printlens_v3_families.tsv` addresses the gallery by row position, so a
     * prepended line would make PrintLens rerank against the wrong card with no error
     * anywhere.
     */
    internal fun splitSignature(raw: ByteArray): SplitIds {
        require(raw.isNotEmpty() && raw.last() == '\n'.code.toByte()) {
            "gallery ids must end with a newline"
        }
        var lastLineStart = 0
        for (index in raw.size - 2 downTo 0) {
            if (raw[index] == '\n'.code.toByte()) {
                lastLineStart = index + 1
                break
            }
        }
        val lastLine = String(raw, lastLineStart, raw.size - 1 - lastLineStart, Charsets.UTF_8).trim()
        return if (lastLine.startsWith(SIGNATURE_TAG)) {
            SplitIds(raw.copyOfRange(0, lastLineStart), lastLine)
        } else {
            SplitIds(raw, null)
        }
    }

    private class ParsedSignature(val keyId: String, val signature: ByteArray)

    private fun parseSignatureLine(line: String): ParsedSignature {
        val parts = line.trim().split(Regex("\\s+"))
        require(parts.size == 3) { "malformed signature line" }
        require(parts[0] == SIGNATURE_TAG) { "unsupported signature tag ${parts[0]}" }
        val decoded = runCatching { Base64.getDecoder().decode(parts[2]) }
            .getOrElse { error("signature is not valid base64") }
        require(decoded.isNotEmpty()) { "signature is empty" }
        return ParsedSignature(parts[1], decoded)
    }

    /**
     * Streams the first [byteCount] bytes through SHA-256.
     *
     * Used for the ids body so the signed prefix is hashed without ever materialising it.
     */
    private fun digestOfPrefix(file: File, byteCount: Long): ByteArray {
        require(byteCount >= 0 && byteCount <= file.length()) { "ids body length is out of range" }
        val digest = MessageDigest.getInstance("SHA-256")
        val marker = SIGNATURE_MARKER
        // Rolling match against the signature marker at a line start, so a second marker
        // is caught without holding the body in memory. "The last line is the signature"
        // and "there is one signature" are different claims; tools that assume the second
        // would disagree about which line is authoritative.
        var atLineStart = true
        var matched = 0
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            var remaining = byteCount
            while (remaining > 0) {
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val read = stream.read(buffer, 0, wanted)
                if (read <= 0) error("ids file ended before its signed body did")
                digest.update(buffer, 0, read)
                for (index in 0 until read) {
                    val byte = buffer[index]
                    if (byte == NEWLINE) {
                        atLineStart = true
                        matched = 0
                        continue
                    }
                    if (atLineStart) {
                        if (byte == marker[matched]) {
                            matched++
                            if (matched == marker.size) {
                                error("gallery ids carry a second signature marker")
                            }
                        } else {
                            atLineStart = false
                            matched = 0
                        }
                    }
                }
                remaining -= read
            }
        }
        return digest.digest()
    }

    /** Streams the file through SHA-256. Reads bytes only -- never parses or opens. */
    private fun digestOf(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream: InputStream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
