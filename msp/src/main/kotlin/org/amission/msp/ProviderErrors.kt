package org.amission.msp

/**
 * Turns a map download's terse error text into something a person can act on. A refused key is by far the most common
 * cause of failed satellite downloads, and a key created only minutes ago is often rejected by some of the provider's
 * servers for a short while before it works everywhere.
 */
object ProviderErrors {
    private val refused = Regex("""(?<!\d)(401|403)(?!\d)""")
    private val tooMany = Regex("""(?<!\d)429(?!\d)""")

    fun describe(raw: String?): String {
        val text = raw.orEmpty()
        val code = refused.find(text)?.value
        return when {
            code != null ->
                "The imagery provider refused some requests (HTTP $code). Check the key in Satellite settings. " +
                    "A key created only a few minutes ago can need a little time before it works everywhere, so try again shortly."
            tooMany.containsMatchIn(text) ->
                "The imagery provider says there were too many requests (HTTP 429). Wait a minute and try again."
            text.isBlank() -> "Download problem."
            else -> "Download problem: $text"
        }
    }
}
