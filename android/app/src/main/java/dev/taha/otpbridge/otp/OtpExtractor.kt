package dev.taha.otpbridge.otp

/**
 * Pulls a one-time code out of a message body.
 *
 * The first version scored candidates by how close they sat to the word "OTP".
 * A real EPFO message killed that idea:
 *
 *   "Dear Member (UAN : XXXX XXXX 7203), OTP to login to Member Interface
 *    is 102710 OTP-ID 8373."
 *
 * 7203 is a fragment of a masked account number and sits *nearer* to "OTP"
 * than the real code does. Proximity is the wrong signal.
 *
 * What actually distinguishes a code is direction and company:
 *   - it is introduced — "... is 102710", "OTP: 445566", "PIN 4821"
 *   - or it introduces — "483920 is your OTP"
 *   - and it is *not* sitting behind a reference marker: UAN, Ref No, A/c
 *     ending, order, txn, an ID, a masked XXXX run, or a currency symbol.
 */
object OtpExtractor {

    // A run of 4-8 digits not touching another digit, letter, or decimal point.
    private val CANDIDATE = Regex("""(?<![\d\p{L}])(\d{4,8})(?![\d\p{L}]|\.\d)""")

    private val KEYWORD = Regex(
        """\botp\b|\bcode\b|verification|verify|one[- ]?time|password|passcode|\bpin\b""",
        RegexOption.IGNORE_CASE,
    )

    // Some services split the code for readability: "123-456", "123 456".
    private val SPLIT_CODE = Regex("""(?<![\d\p{L}])(\d{3})[- ](\d{3})(?![\d\p{L}])""")

    /**
     * Text immediately before a candidate that marks it as a reference number
     * rather than a code. Anything matching is out of the running entirely.
     */
    private val DISQUALIFIER = Regex(
        """(?:\bx{3,}\b|\*{3,}|\buan\b|\baadhaar\b|\bpan\b""" +
            """|\bref(?:erence)?\b(?:\s*(?:no\.?|num(?:ber)?|id))?""" +
            """|\bid\b|\ba/c\b|\bacct\b|\baccount\b|\bending\b""" +
            """|\border\b|\btxn\b|\btransaction\s+no\.?|\binvoice\b|\bticket\b|\breceipt\b""" +
            """|\bcard\b|\bmobile\b|\bphone\b""" +
            """|\brs\.?|\binr\b|\busd\b|\$)\s*[:.#\-]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Text immediately before a candidate that introduces it as the code. */
    private val INTRODUCER = Regex(
        """(?:\bis\b|\bwas\b|\bcode\b|\botp\b|\bpin\b|\bpassword\b|\bpasscode\b|\bpwd\b|[:=])""" +
            """\s*[:=\-]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Text immediately after a candidate in the "483920 is your OTP" idiom. */
    private val FOLLOWED_BY_CLAIM = Regex(
        """^\s*(?:is|was)\s+(?:your|the|my)?\s*(?:\w+\s+){0,3}?""" +
            """(?:otp|code|password|passcode|pin)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Units that mean the number was a duration or amount, not a code. */
    private val TRAILING_UNIT = Regex(
        """^\s*(?:mins?|minutes?|secs?|seconds?|hrs?|hours?|days?|%)\b""",
        RegexOption.IGNORE_CASE,
    )

    private const val CONTEXT = 26

    /**
     * The gate the SMS receiver uses.
     *
     * Since every sender is allowed by default, this is what keeps ordinary
     * messages from being forwarded: a message must both *read* like a one-time
     * code message and actually contain a code. "Your order 4455 shipped" has a
     * number but no keyword; "Your OTP request failed" has a keyword but no
     * code. Neither is forwarded.
     */
    fun extractOtp(body: String): String? {
        if (body.isEmpty()) return null
        if (!KEYWORD.containsMatchIn(body)) return null
        return extract(body)
    }

    fun extract(body: String): String? {
        if (body.isEmpty()) return null

        // Rejoin split codes first so "123-456" is one 6-digit candidate rather
        // than two 3-digit runs the candidate rule would skip.
        val text = SPLIT_CODE.replace(body) { "${it.groupValues[1]}${it.groupValues[2]}" }

        var best: String? = null
        var bestScore = Int.MIN_VALUE

        for (m in CANDIDATE.findAll(text)) {
            val value = m.groupValues[1]
            val before = text.substring(maxOf(0, m.range.first - CONTEXT), m.range.first)
            val after = text.substring(minOf(text.length, m.range.last + 1))
                .take(CONTEXT)

            // A reference number is never the code, however well placed.
            if (DISQUALIFIER.containsMatchIn(before)) continue

            var score = 0
            if (INTRODUCER.containsMatchIn(before)) score += 50
            if (FOLLOWED_BY_CLAIM.containsMatchIn(after)) score += 40
            if (TRAILING_UNIT.containsMatchIn(after)) score -= 30

            // Six digits is overwhelmingly the most common OTP length.
            score += when (value.length) {
                6 -> 8
                4 -> 5
                5 -> 3
                else -> 2
            }

            // Strictly greater, so ties go to the earliest candidate — which is
            // how "OTP is X ... Y" reads.
            if (score > bestScore) {
                bestScore = score
                best = value
            }
        }

        return best
    }
}
