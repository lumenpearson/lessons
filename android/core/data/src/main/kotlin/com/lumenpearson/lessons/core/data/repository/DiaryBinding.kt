package com.lumenpearson.lessons.core.data.repository

/**
 * Which diary a class reads, as the join told this phone — so a family that
 * joined by code can sign in to their diary without searching for their
 * school.
 *
 * It rides the join's answer and nothing else (`JoinResponse.diary`): the
 * server built it through its own allow-list, so a class bound to a region the
 * server cannot reach, one that takes no password, or a «Сетевой город»
 * binding without a school arrives as no binding at all. A phone that joined
 * before bindings existed holds none until it joins again, which costs it the
 * prefill and nothing else.
 *
 * @property region the catalog's key; `null` for Petersburg.
 * @property schoolId the upstream's own school id; `null` for Petersburg.
 */
data class DiaryBinding(
    val provider: DiaryProviderKey,
    val region: String?,
    val schoolId: Long?,
    val schoolName: String?,
) {

    /**
     * The sign-in target for [login] at this binding, or `null` when a
     * «Сетевой город» binding is missing the region or the school it cannot
     * sign in without. The zone is a placeholder until registration answers
     * with the server's own, which is what gets stored.
     */
    fun targetFor(login: String): DiaryTarget? = when (provider) {
        DiaryProviderKey.PETERSBURG -> DiaryTarget.petersburg(login)
        DiaryProviderKey.NETSCHOOL -> {
            val region = region?.takeIf { it.isNotBlank() }
            val school = schoolId
            if (region == null || school == null) {
                null
            } else {
                DiaryTarget(
                    provider = provider,
                    region = region,
                    schoolId = school,
                    schoolName = schoolName,
                    login = login,
                )
            }
        }
    }
}
