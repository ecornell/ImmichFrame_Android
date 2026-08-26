package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedServerSettingsStoreTest {
    @Test
    fun `settings codec round trips last known rendering state`() {
        val settings = Helpers.ServerSettings(
            margin = "0",
            interval = 30,
            transitionDuration = 1.5,
            downloadImages = false,
            renewImagesDuration = 60,
            showClock = true,
            clockFormat = "HH:mm",
            showPhotoDate = true,
            photoDateFormat = "MM/dd/yyyy",
            showImageDesc = false,
            showPeopleDesc = false,
            showImageLocation = true,
            imageLocationFormat = "City",
            primaryColor = "#ffffff",
            secondaryColor = "#000000",
            style = "default",
            baseFontSize = "16px",
            showWeatherDescription = false,
            unattendedMode = true,
            imageZoom = true,
            imageFill = false,
            layout = "split",
            language = "en",
        )

        assertEquals(settings, CachedServerSettingsStore.decode(CachedServerSettingsStore.encode(settings)))
    }

    @Test
    fun `settings codec rejects missing or malformed state`() {
        assertNull(CachedServerSettingsStore.decode(null))
        assertNull(CachedServerSettingsStore.decode(""))
        assertNull(CachedServerSettingsStore.decode("not-json"))
    }
}
