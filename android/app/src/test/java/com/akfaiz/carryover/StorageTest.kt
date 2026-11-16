package com.akfaiz.carryover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    @Test
    fun privateAppFolders() {
        assertTrue(Storage.isPrivateAppFolder("/storage/emulated/0/Android/data/com.retroarch/files/saves"))
        assertTrue(Storage.isPrivateAppFolder("/storage/emulated/0/Android/obb"))
        assertFalse(Storage.isPrivateAppFolder("/storage/emulated/0/RetroArch/saves"))
        assertFalse(Storage.isPrivateAppFolder("/storage/emulated/0/Android/media/x"))
    }

    @Test
    fun suggestionsByEmulatorName() {
        assertEquals(listOf("{storage}/RetroArch/saves"), Storage.suggestions("RetroArch (Android)"))
        assertEquals(listOf("{storage}/PSP/SAVEDATA"), Storage.suggestions("PPSSPP"))
        assertEquals(emptyList<String>(), Storage.suggestions("Dolphin"))
    }
}
