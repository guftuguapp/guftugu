package com.guftugu.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure helpers of the repository (the rest needs Keystore + BiometricPrompt, i.e. a phone). */
class AuthRepositoryImplTest {
    @Test
    fun `device name prefill is brand-cased and never repeats the brand`() {
        assertEquals("Huawei AQM-LX1", AuthRepositoryImpl.deviceName("HUAWEI", "AQM-LX1"))
        assertEquals("Samsung SM-G991B", AuthRepositoryImpl.deviceName("samsung", "SM-G991B"))
        assertEquals("Oppo CPH2083", AuthRepositoryImpl.deviceName("OPPO", "CPH2083"))
        assertEquals("Google Pixel 8", AuthRepositoryImpl.deviceName("Google", "Pixel 8"))
        assertEquals("Xiaomi 13", AuthRepositoryImpl.deviceName("Xiaomi", "Xiaomi 13"))
        assertEquals("Pixel 8", AuthRepositoryImpl.deviceName("", "Pixel 8"))
        assertEquals("Google", AuthRepositoryImpl.deviceName("google", null))
    }
}
