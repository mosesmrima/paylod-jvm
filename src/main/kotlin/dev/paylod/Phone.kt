package dev.paylod

/**
 * Kenyan MSISDN normalisation — a byte-for-byte port of the Node SDK's `phone.ts`, which is the
 * reference implementation of the canonical backend spec (`_shared/daraja/primitives.ts`).
 *
 * Accepts `0712345678`, `+254712345678`, `254712345678`, `712345678`, with or without
 * spaces/dashes. Emits the canonical `2547XXXXXXXX` / `2541XXXXXXXX` wire form.
 */
object Phone {

    /** Validates RAW user input in any accepted Kenyan form (before normalization). */
    @JvmField
    val MSISDN_INPUT_RE = Regex("^(?:\\+?254|0)?[17]\\d{8}$")

    private val NON_DIGITS = Regex("\\D+")
    private val WIRE_RE = Regex("^254[17]\\d{8}$")

    /** True if [input] is an acceptable Kenyan MSISDN form. Does not throw. */
    @JvmStatic
    fun isValidMsisdn(input: String?): Boolean =
        input != null && MSISDN_INPUT_RE.matches(input.trim())

    /**
     * Normalise any accepted Kenyan phone format to the canonical `2547XXXXXXXX` wire form.
     *
     * @throws PaylodInvalidRequestException if the input is blank or not a valid Kenyan number.
     */
    @JvmStatic
    fun normalize(input: String?): String {
        if (input == null || input.trim().isEmpty()) {
            throw PaylodInvalidRequestException("phone is required")
        }
        val digits = input.replace(NON_DIGITS, "")

        val msisdn: String = when {
            digits.startsWith("254") -> digits
            digits.startsWith("0") -> "254" + digits.substring(1)
            digits.startsWith("7") || digits.startsWith("1") -> "254$digits"
            else -> throw PaylodInvalidRequestException("unrecognized Kenyan phone format: $input")
        }

        if (!WIRE_RE.matches(msisdn)) {
            throw PaylodInvalidRequestException("not a valid Kenyan phone number: $input")
        }
        return msisdn
    }
}
