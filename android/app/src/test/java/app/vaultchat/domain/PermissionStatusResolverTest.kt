package app.vaultchat.domain

import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.domain.permissions.PermissionStatusResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStatusResolverTest {
    @Test
    fun `granted wins over everything`() {
        assertEquals(PermissionStatus.Granted, PermissionStatusResolver.resolve(true, true, true))
    }

    @Test
    fun `never asked is requestable without rationale`() {
        assertEquals(PermissionStatus.Requestable(false), PermissionStatusResolver.resolve(false, false, false))
    }

    @Test
    fun `denied once shows rationale`() {
        assertEquals(PermissionStatus.Requestable(true), PermissionStatusResolver.resolve(false, true, true))
    }

    @Test
    fun `asked before without rationale is permanently denied`() {
        assertEquals(PermissionStatus.PermanentlyDenied, PermissionStatusResolver.resolve(false, false, true))
    }

    @Test
    fun `notification permission only exists from Android 13`() {
        assertFalse(AppPermission.NOTIFICATIONS.isRuntimePermissionOn(32))
        assertTrue(AppPermission.NOTIFICATIONS.isRuntimePermissionOn(33))
        assertTrue(AppPermission.CAMERA.isRuntimePermissionOn(29))
    }
}
