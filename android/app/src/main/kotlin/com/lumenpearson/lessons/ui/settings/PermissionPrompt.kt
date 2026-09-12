package com.lumenpearson.lessons.ui.settings

/**
 * What the one button on a permission card should do, decided from what the
 * system currently says about that permission.
 *
 * Pure on purpose, and the only part of the permissions feature that is. The
 * decision is the part that is easy to get wrong and impossible to see: a
 * runtime permission that has been refused twice looks exactly like one that
 * has never been asked for — [android.app.Activity.shouldShowRequestPermissionRationale]
 * answers false to both — and the difference is whether tapping the button
 * shows a dialog or does nothing at all. The module has no instrumented tests,
 * so the branch that decides that lives here, free of `Context`, and is tested.
 */
enum class PermissionPrompt {

    /** Ask the platform itself: there is a dialog, and it will still appear. */
    RequestRuntime,

    /** Send the user to a system page, because nothing else is left to try. */
    OpenSettings,

    /**
     * Nothing to ask for. Either the permission is held, or this Android version
     * does not have it — the card for it still opens settings, because a granted
     * permission is also the one you come looking for in order to turn it off.
     */
    Done,

    ;

    companion object {

        /**
         * The action for one permission.
         *
         * @param relevant whether this Android version has the permission at
         *   all; see [AppPermission.isRelevant].
         * @param granted what the system answers right now, asked fresh rather
         *   than remembered; see [AppPermission.isGranted].
         * @param runtime whether a runtime dialog is still available for it —
         *   false both for the settings-only entries (exact alarms, battery) and
         *   for a notification permission that is already held while the user
         *   has switched notifications off by hand, where the dialog would
         *   return "granted" and change nothing.
         * @param requested whether this process has already put that dialog in
         *   front of the user. Needed because [rationaleShown] cannot tell
         *   "never asked" from "asked and refused for good".
         * @param rationaleShown what `shouldShowRequestPermissionRationale`
         *   answers: true only between the first refusal and the second.
         */
        fun of(
            relevant: Boolean,
            granted: Boolean,
            runtime: Boolean,
            requested: Boolean,
            rationaleShown: Boolean,
        ): PermissionPrompt = when {
            !relevant || granted -> Done
            !runtime -> OpenSettings
            deniedPermanently(
                runtime = runtime,
                granted = granted,
                requested = requested,
                rationaleShown = rationaleShown,
            ) -> OpenSettings

            else -> RequestRuntime
        }

        /**
         * Whether the platform has stopped offering the dialog for good.
         *
         * The three conditions have to hold together. After two refusals the
         * system silently returns "denied" from every further request, and
         * `shouldShowRequestPermissionRationale` goes back to false — the same
         * false it gave before the first request. Only the app's own memory of
         * having asked separates the two, which is why [requested] is part of
         * this and not an implementation detail of the caller.
         *
         * The first tap after a cold start therefore still launches a request
         * that the system answers instantly and invisibly: nothing has been
         * asked *in this process* yet. That one tap is the cost of the platform
         * not exposing the state, and the card corrects itself the moment the
         * result comes back.
         */
        fun deniedPermanently(
            runtime: Boolean,
            granted: Boolean,
            requested: Boolean,
            rationaleShown: Boolean,
        ): Boolean = runtime && !granted && requested && !rationaleShown
    }
}
