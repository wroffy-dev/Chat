package app.vaultchat.domain

import app.vaultchat.domain.location.LocationBlock
import app.vaultchat.domain.location.LocationGate
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationGateTest {
    @Test
    fun `the app is usable only with sharing on, permission granted and the phone's location on`() {
        assertEquals(LocationBlock.NONE, LocationGate.decide(sharingOn = true, permissionGranted = true, deviceLocationOn = true))
    }

    @Test
    fun `consent comes first, then permission, then the phone's setting`() {
        assertEquals(LocationBlock.CONSENT, LocationGate.decide(sharingOn = null, permissionGranted = false, deviceLocationOn = false))
        assertEquals(LocationBlock.CONSENT, LocationGate.decide(sharingOn = false, permissionGranted = true, deviceLocationOn = true))
        assertEquals(LocationBlock.PERMISSION, LocationGate.decide(sharingOn = true, permissionGranted = false, deviceLocationOn = true))
        assertEquals(LocationBlock.DEVICE_OFF, LocationGate.decide(sharingOn = true, permissionGranted = true, deviceLocationOn = false))
    }
}
