package com.lumenpearson.lessons.core.data.docs

/**
 * `ru` or `en`: a phone in any other language reads the English text.
 *
 * One rule for every document the app ships in two languages — the guide and
 * the terms and privacy policy. It used to be private to the guide's
 * repository, and a second copy of it for the legal texts would have been two
 * rules the day a third language reached one of them: a reader would get the
 * guide in one language and the policy they accepted in the other.
 */
internal fun docsLanguage(language: String): String =
    if (language.lowercase().startsWith("ru")) "ru" else "en"
