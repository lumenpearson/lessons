package com.lumenpearson.lessons.core.model

/**
 * A released version, as a thing that can be compared.
 *
 * The update check exists to answer one question — is what GitHub is offering
 * newer than what is installed — and that question is only as good as the
 * comparison behind it. String comparison gets it wrong at the first
 * double-digit release ("0.10.0" < "0.9.0"), and a plain number-by-number
 * comparison gets it wrong at the first release candidate, which is exactly
 * when it matters most.
 *
 * So this follows the ordering rules of semantic versioning, with two
 * concessions to reality:
 *
 *  - **Any number of components.** Semver insists on three. Tags in the wild
 *    are "v1", "v1.2" and "v1.2.3.4", and a parser that rejects them would
 *    report "no updates" forever rather than say why. A missing component
 *    counts as zero, so 1.2 and 1.2.0 are the same version.
 *  - **Build metadata is dropped, not compared.** Semver says a `+build` suffix
 *    is not part of identity, and it is dropped here for the same reason: it
 *    describes how a binary was made, not what it is.
 *
 * The pre-release rule is semver's own and is the whole point of the type: a
 * version carrying a suffix is *older* than the same numbers without one, so
 * 1.0.0-rc.1 does not offer itself as an upgrade over 1.0.0.
 *
 * @property numbers the dotted components, most significant first.
 * @property preRelease the part after the first `-`, or `null` for a final
 *   release. Compared identifier by identifier, numerically where both sides
 *   are numeric, which is what puts rc.2 after rc.10 the right way round.
 */
data class AppVersion(
    val numbers: List<Int>,
    val preRelease: String?,
) : Comparable<AppVersion> {

    /** True for a release candidate, beta, alpha — anything not final. */
    val isPreRelease: Boolean get() = preRelease != null

    override fun compareTo(other: AppVersion): Int {
        val width = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until width) {
            val mine = numbers.getOrElse(index) { 0 }
            val theirs = other.numbers.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        numbers.joinToString(".") + if (preRelease != null) "-$preRelease" else ""

    companion object {

        /**
         * Reads a tag or a `versionName`.
         *
         * Tolerant about the shapes a release tag actually takes: a leading `v`
         * or `V`, surrounding whitespace, and a trailing build suffix are all
         * accepted, because the alternative is a null that the caller can only
         * turn into a silent "no update".
         *
         * @return `null` when there is no leading number at all, which is the
         *   one case where guessing would be worse than admitting defeat.
         */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.trim() ?: return null
            if (text.isEmpty()) return null
            // Build metadata first: it may itself contain a `-`, and dropping it
            // before the pre-release split keeps that out of the comparison.
            val withoutBuild = text.substringBefore('+')
            val core = withoutBuild.substringBefore('-')
            val preRelease = withoutBuild.substringAfter('-', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }

            val numbers = core.split('.').map { part ->
                // Trailing junk on a component ("1.2.3final") is cut rather than
                // rejected; a component that starts with junk is not a number.
                val digits = part.takeWhile { it.isDigit() }
                if (digits.isEmpty()) return@parse null
                digits.toIntOrNull() ?: return@parse null
            }
            if (numbers.isEmpty()) return null
            return AppVersion(numbers = numbers, preRelease = preRelease)
        }

        /**
         * Whether [candidate] is worth offering over [installed].
         *
         * Unparseable on either side means no: an update prompt that cannot say
         * what it is upgrading from is worse than no prompt.
         */
        fun isNewer(candidate: String?, installed: String?): Boolean {
            val new = parse(candidate) ?: return false
            val old = parse(installed) ?: return false
            return new > old
        }

        private fun comparePreRelease(mine: String?, theirs: String?): Int = when {
            mine == null && theirs == null -> 0
            // A final release outranks any pre-release of the same numbers.
            mine == null -> 1
            theirs == null -> -1
            else -> compareIdentifiers(mine.split('.'), theirs.split('.'))
        }

        private fun compareIdentifiers(mine: List<String>, theirs: List<String>): Int {
            for (index in 0 until maxOf(mine.size, theirs.size)) {
                // A pre-release that runs out of identifiers first is the
                // earlier one: rc < rc.1.
                val a = mine.getOrNull(index) ?: return -1
                val b = theirs.getOrNull(index) ?: return 1
                val numA = a.toIntOrNull()
                val numB = b.toIntOrNull()
                val verdict = when {
                    numA != null && numB != null -> numA.compareTo(numB)
                    // Numeric identifiers always have lower precedence than
                    // alphanumeric ones, which is semver's rule verbatim.
                    numA != null -> -1
                    numB != null -> 1
                    else -> a.compareTo(b)
                }
                if (verdict != 0) return verdict
            }
            return 0
        }
    }
}
