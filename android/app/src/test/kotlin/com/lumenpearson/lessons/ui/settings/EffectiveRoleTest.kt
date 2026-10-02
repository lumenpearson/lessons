package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which role the settings root draws its bar with (#228).
 *
 * The debug button belongs to a class manager, and the role used to come from
 * this launch's `/me` alone — unknown at every start, so the button arrived a
 * moment late, or not at all until the page was opened again.
 */
class EffectiveRoleTest {

    private fun link(role: ClassRole?) = DeviceLink(
        deviceName = "Pixel",
        linked = role != null,
        role = role,
        canEdit = role != null,
        linkCode = null,
        botDeepLink = null,
    )

    @Test
    fun `before the server answers, the remembered role is drawn`() {
        assertEquals(
            ClassRole.OWNER,
            SettingsUiState(deviceLink = DeviceLinkState.Idle, cachedRole = ClassRole.OWNER).effectiveRole,
        )
        assertEquals(
            ClassRole.OWNER,
            SettingsUiState(
                deviceLink = DeviceLinkState.Failed(IllegalStateException("offline"), known = null),
                cachedRole = ClassRole.OWNER,
            ).effectiveRole,
        )
    }

    @Test
    fun `a fresh answer wins over the remembered one, no role included`() {
        assertNull(
            SettingsUiState(deviceLink = DeviceLinkState.Ready(link(null)), cachedRole = ClassRole.OWNER).effectiveRole,
        )
        assertEquals(
            ClassRole.ADMIN,
            SettingsUiState(deviceLink = DeviceLinkState.Ready(link(ClassRole.ADMIN)), cachedRole = null).effectiveRole,
        )
    }
}
