package kr.callbrief

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A disconnected native client must reject work before reading settings, audio, or scheduler state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35],shadows=[ShadowTextKeys::class])
class NativeTransmissionGuardTest : ProtectedTextHostTestBase() {
    private fun unreadableContext(): Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            throw AssertionError("Disconnected schedule accessed settings")
        override fun getContentResolver(): ContentResolver =
            throw AssertionError("Disconnected schedule accessed recordings")
        override fun getApplicationContext(): Context =
            throw AssertionError("Disconnected schedule accessed WorkManager")
    }

    @Test fun periodicWorkDoesNotReadOrEnqueueBeforeNativeSupport() {
        SyncSchedule.ensure(unreadableContext())
    }

    @Test fun legacyDailyConsentDoesNotEnableAJob() {
        val oldConsent = JSONObject().put("cloudEnabled", true).put("pendingRevocation", false)
        SyncSchedule.scheduleDaily(unreadableContext(), oldConsent)
    }

    @Test fun manualRequestRejectsWithoutSettingsOrAudioAccess() {
        try {
            SyncSchedule.manual(unreadableContext())
            fail("Native DISCONNECTED accepted a request")
        } catch (e: IllegalArgumentException) {
            assertEquals(NativeAnalysisAvailability.MESSAGE, e.message)
        }
    }
    @Test fun legacyClientCannotUseConfiguredTokensOrOpenAConnection() {
        try {
            ServiceApi("synthetic_invalid_endpoint", "synthetic_short_token")
            fail("Disconnected legacy client was created")
        } catch (e: IllegalArgumentException) { assertEquals(NativeAnalysisAvailability.MESSAGE, e.message) }
        try {
            ServiceApi.from(JSONObject())
            fail("Disconnected client read missing configuration")
        } catch (e: IllegalArgumentException) { assertEquals(NativeAnalysisAvailability.MESSAGE, e.message) }
    }
    @Test fun disconnectedStateCannotAnnounceAReadyBriefing() {
        LocalNotifications.reportReady(unreadableContext(), "2026-10-10")
    }
}
