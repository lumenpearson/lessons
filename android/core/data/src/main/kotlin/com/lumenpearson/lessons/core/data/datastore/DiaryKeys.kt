package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.DiaryTargetCodec

/**
 * The diary's keys, apart from the class's and never written by the same
 * method: leaving a class must not sign a parent out of the diary, and signing
 * out of the diary must not unjoin the class.
 *
 * `diary_target` is one JSON value rather than six loose keys, so a write cut
 * short can never leave a region beside another diary's school.
 * `diary_student_id` is apart from it because it changes on its own — a
 * different pupil picked — and is forgotten on its own, when the account
 * changes.
 */
internal object DiaryKeys {
    val TOKEN = stringPreferencesKey("diary_token")
    val LOGIN = stringPreferencesKey("diary_login")
    val TARGET = stringPreferencesKey("diary_target")
    val STUDENT_ID = longPreferencesKey("diary_student_id")
}

/**
 * Which diary this phone signs in to, read from one emission — so whatever
 * decides a mode from it and from the memberships reads both from the same
 * moment rather than combining two flows that can disagree in between.
 *
 * A stored target that this build cannot read — a later version, a provider it
 * does not know — is no target at all, rather than a guess: signing a third
 * diary's account in to Petersburg would be worse than asking. An install from
 * before targets, holding a token and a login, is Petersburg in Moscow time,
 * which is the only diary it can have signed in to.
 */
internal fun Preferences.toDiaryTarget(): DiaryTarget? {
    this[DiaryKeys.TARGET]?.let { stored -> return DiaryTargetCodec.decode(stored) }
    this[DiaryKeys.TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    val login = this[DiaryKeys.LOGIN]?.takeIf { it.isNotBlank() } ?: return null
    return DiaryTarget.petersburg(login)
}

/**
 * Our bearer, the login and the target, in one transaction. The bearer is
 * written as it is handed in, so the caller seals it first — see
 * `LessonsPreferences.writeDiarySession`.
 */
internal fun MutablePreferences.putDiarySession(value: DiarySession) {
    this[DiaryKeys.TOKEN] = value.token
    this[DiaryKeys.LOGIN] = value.login
    this[DiaryKeys.TARGET] = DiaryTargetCodec.encode(value.target)
}

/**
 * A bare `401`: our bearer is dead, the account is not. The login and the
 * target stay, so the sign-in that follows is to the same diary.
 *
 * An install from before targets has only a token and a login, and its target
 * is *derived* from the pair — so removing the token would remove the target
 * with it. It is written down first, in the same transaction, as the Petersburg
 * target it has always been read as.
 */
internal fun MutablePreferences.dropDiaryToken() {
    if (DiaryKeys.TARGET !in this) {
        toDiaryTarget()?.let { this[DiaryKeys.TARGET] = DiaryTargetCodec.encode(it) }
    }
    remove(DiaryKeys.TOKEN)
}

/** Signing out: everything about the diary account, and nothing about the class. */
internal fun MutablePreferences.dropDiary() {
    remove(DiaryKeys.TOKEN)
    remove(DiaryKeys.LOGIN)
    remove(DiaryKeys.TARGET)
    remove(DiaryKeys.STUDENT_ID)
}

/**
 * `null` unless a diary sign-in has actually stored a token — and `null` too
 * when its target is one this build cannot read (see [toDiaryTarget]): a bearer
 * sent for an account the app cannot name would be answered for somebody the
 * screens cannot say.
 *
 * The token is the one stored, sealed since #201; [openDiarySession] is the
 * one to read for a bearer.
 */
internal fun Preferences.toDiarySession(): DiarySession? {
    val token = this[DiaryKeys.TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    val login = this[DiaryKeys.LOGIN].orEmpty()
    val target = if (DiaryKeys.TARGET in this) {
        toDiaryTarget() ?: return null
    } else {
        DiaryTarget.petersburg(login)
    }
    return DiarySession(login = login, token = token, target = target)
}
