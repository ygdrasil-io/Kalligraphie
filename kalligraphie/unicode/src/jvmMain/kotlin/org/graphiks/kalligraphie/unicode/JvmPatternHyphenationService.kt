package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.HyphenationMinimums
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.HyphenationServiceIdentity
import java.io.InputStream
import java.security.MessageDigest

/**
 * JVM provider of the pinned American-English hyphenation pattern data.
 *
 * The patterns are the US English patterns published in the `hyph-utf8`
 * package (v1.0.0, patterns by Gerard D.C. Kuiken, copyright 1990–2005), whose
 * licensing notice permits copying and distribution with the notice intact.
 * The provider verifies the SHA-256 digest of the embedded resource before
 * building the service, so a corrupt or swapped pattern set can never change
 * observable output silently.
 */
public object JvmPatternHyphenationService {
    /** SHA-256 of the embedded `hyph-en-us.pat.txt` pattern resource. */
    public val englishPatternSha256: String = ENGLISH_PATTERN_SHA256

    /** Versioned identity of the pinned American-English pattern set. */
    public val englishIdentity: HyphenationServiceIdentity = HyphenationServiceIdentity(
        providerId = "hyph-utf8-patterns",
        dataRevision = "hyph-en-us@2005-05-30",
        languages = listOf("en", "en-US"),
    )

    /**
     * Creates the pinned American-English [HyphenationService] from the
     * embedded, digest-verified pattern resource.
     *
     * The returned service owns no resource and is immutable; its pattern set
     * is bundled with this class. Missing or corrupt embedded data throws
     * [IllegalStateException], which is a packaging error rather than a
     * runtime data condition.
     */
    public fun english(): HyphenationService = english(ENGLISH_PATTERN_SHA256)

    /**
     * Creates the American-English service from [patterns] after verifying
     * [patterns] against [expectedSha256].
     */
    public fun english(patternText: String, expectedSha256: String): HyphenationService {
        val bytes = patternText.toByteArray(Charsets.US_ASCII)
        require(sha256(bytes) == expectedSha256) {
            "The supplied hyphenation pattern set failed its SHA-256 verification."
        }
        return PatternHyphenationService(
            patterns = patternText.lineSequence().toList(),
            identity = englishIdentity,
            minimums = HyphenationMinimums(2, 3),
        )
    }

    /** Loads and verifies the embedded pattern resource. */
    public fun english(expectedSha256: String): HyphenationService {
        val resource = "/hyphenation/hyph-en-us.pat.txt"
        val stream: InputStream = JvmPatternHyphenationService::class.java.getResourceAsStream(resource)
            ?: throw IllegalStateException("The embedded hyphenation pattern resource $resource is missing.")
        val text = stream.use { input -> input.readBytes().toString(Charsets.US_ASCII) }
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val digest = sha256(bytes)
        check(digest == expectedSha256) {
            "The embedded hyphenation pattern resource failed verification: expected $expectedSha256, got $digest."
        }
        return PatternHyphenationService(
            patterns = text.lineSequence().toList(),
            identity = englishIdentity,
            minimums = HyphenationMinimums(2, 3),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** SHA-256 of the vendored `hyph-en-us.pat.txt` resource. */
private const val ENGLISH_PATTERN_SHA256: String = "0f57318b878b132547ae92db39a6e1d1cf2a05d9008874955d6ecb910007a463"
