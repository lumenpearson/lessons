package com.lumenpearson.lessons.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one part of the permissions feature that can be tested without a device.
 *
 * The interesting case is not "granted or not" — it is that the platform gives
 * the same answer, false, for a permission that has never been asked about and
 * for one it will never ask about again, and that the button has to do two
 * different things in those two cases.
 */
class PermissionPromptTest {

    /** Everything held: a card that still opens settings, so it can be turned off. */
    @Test
    fun `a granted permission asks for nothing`() {
        assertEquals(
            PermissionPrompt.Done,
            PermissionPrompt.of(
                relevant = true,
                granted = true,
                runtime = true,
                requested = true,
                rationaleShown = false,
            ),
        )
    }

    /**
     * Exact alarms below Android 12. The entry is filtered out before it reaches
     * a card, but the decision must not depend on that having happened.
     */
    @Test
    fun `a permission this Android version does not have asks for nothing`() {
        assertEquals(
            PermissionPrompt.Done,
            PermissionPrompt.of(
                relevant = false,
                granted = false,
                runtime = false,
                requested = false,
                rationaleShown = false,
            ),
        )
    }

    /** Exact alarms and battery exemption: no dialog exists, so the page it is. */
    @Test
    fun `a permission with no dialog goes straight to settings`() {
        assertEquals(
            PermissionPrompt.OpenSettings,
            PermissionPrompt.of(
                relevant = true,
                granted = false,
                runtime = false,
                requested = false,
                rationaleShown = false,
            ),
        )
    }

    /** The first tap on a fresh install, which is the only one that has a dialog waiting. */
    @Test
    fun `a runtime permission never asked about is asked for`() {
        assertEquals(
            PermissionPrompt.RequestRuntime,
            PermissionPrompt.of(
                relevant = true,
                granted = false,
                runtime = true,
                requested = false,
                rationaleShown = false,
            ),
        )
    }

    /** Refused once: the system will show the dialog again, with a rationale. */
    @Test
    fun `a runtime permission refused once is asked for again`() {
        assertEquals(
            PermissionPrompt.RequestRuntime,
            PermissionPrompt.of(
                relevant = true,
                granted = false,
                runtime = true,
                requested = true,
                rationaleShown = true,
            ),
        )
    }

    /**
     * The case this whole enum exists for. Asked, still denied, and the platform
     * has gone back to saying no rationale is needed — which here means it will
     * not show the dialog again, so launching one would do nothing visible at
     * all and the button has to send the user to settings instead.
     */
    @Test
    fun `a runtime permission refused for good opens settings instead`() {
        assertEquals(
            PermissionPrompt.OpenSettings,
            PermissionPrompt.of(
                relevant = true,
                granted = false,
                runtime = true,
                requested = true,
                rationaleShown = false,
            ),
        )
    }

    /**
     * POST_NOTIFICATIONS held while notifications are switched off by hand. No
     * dialog is left to show — a request would return "granted" and change
     * nothing — so the remaining switch is the one in settings.
     */
    @Test
    fun `a held runtime permission with the switch still off opens settings`() {
        assertEquals(
            PermissionPrompt.OpenSettings,
            PermissionPrompt.of(
                relevant = true,
                granted = false,
                runtime = false,
                requested = true,
                rationaleShown = false,
            ),
        )
    }

    /** Granted is answered before anything else is looked at. */
    @Test
    fun `granting it after a refusal clears the refusal`() {
        assertEquals(
            PermissionPrompt.Done,
            PermissionPrompt.of(
                relevant = true,
                granted = true,
                runtime = true,
                requested = true,
                rationaleShown = true,
            ),
        )
    }

    /**
     * The trap: `shouldShowRequestPermissionRationale` is false here too, and
     * reading it alone would send a first-time user to a settings page instead
     * of showing them the dialog that was waiting for them.
     */
    @Test
    fun `never having asked is not a permanent refusal`() {
        assertFalse(
            PermissionPrompt.deniedPermanently(
                runtime = true,
                granted = false,
                requested = false,
                rationaleShown = false,
            ),
        )
    }

    @Test
    fun `a refusal the system will still reconsider is not permanent`() {
        assertFalse(
            PermissionPrompt.deniedPermanently(
                runtime = true,
                granted = false,
                requested = true,
                rationaleShown = true,
            ),
        )
    }

    @Test
    fun `asked, denied and no rationale left is a permanent refusal`() {
        assertTrue(
            PermissionPrompt.deniedPermanently(
                runtime = true,
                granted = false,
                requested = true,
                rationaleShown = false,
            ),
        )
    }

    /** A settings-only permission is never refused permanently; it is never asked. */
    @Test
    fun `a permission with no dialog is never permanently refused`() {
        assertFalse(
            PermissionPrompt.deniedPermanently(
                runtime = false,
                granted = false,
                requested = true,
                rationaleShown = false,
            ),
        )
    }

    /** Nothing is refused while it is held, whatever the history says. */
    @Test
    fun `a granted permission is never permanently refused`() {
        assertFalse(
            PermissionPrompt.deniedPermanently(
                runtime = true,
                granted = true,
                requested = true,
                rationaleShown = false,
            ),
        )
    }

    /**
     * Every combination, against the rule written out longhand. The table is
     * small enough to enumerate, and enumerating it is the only way to be sure
     * a later branch has not quietly swallowed an earlier one.
     */
    @Test
    fun `every combination resolves to exactly one action`() {
        val flags = listOf(false, true)

        flags.forEach { relevant ->
            flags.forEach { granted ->
                flags.forEach { runtime ->
                    flags.forEach { requested ->
                        flags.forEach { rationaleShown ->
                            val prompt = PermissionPrompt.of(
                                relevant = relevant,
                                granted = granted,
                                runtime = runtime,
                                requested = requested,
                                rationaleShown = rationaleShown,
                            )
                            val expected = when {
                                !relevant || granted -> PermissionPrompt.Done
                                !runtime -> PermissionPrompt.OpenSettings
                                requested && !rationaleShown -> PermissionPrompt.OpenSettings
                                else -> PermissionPrompt.RequestRuntime
                            }

                            assertEquals(
                                "relevant=$relevant granted=$granted runtime=$runtime " +
                                    "requested=$requested rationale=$rationaleShown",
                                expected,
                                prompt,
                            )
                        }
                    }
                }
            }
        }
    }
}
