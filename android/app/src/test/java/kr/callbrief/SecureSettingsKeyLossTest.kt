package kr.callbrief

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Real host AES-GCM and preference shadow. PUBLIC TEST KEY; no AndroidKeyStore claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class])
class SecureSettingsKeyLossTest : ProtectedTextHostTestBase() {
    class FixtureKeys : SecureSettings.KeyAccess {
        var value:SecretKey?=null;var creations=0
        override fun existing()=value
        override fun createForFirstEmptyStore():SecretKey { creations++;return SecretKeySpec(ByteArray(32) { 11 },"AES").also { value=it } }
    }
    private lateinit var context:Context
    @Before fun reset() { context=RuntimeEnvironment.getApplication();context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit() }
    private fun sealed()=context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).getString("state",null)
    private fun rejects(action:()->Unit) { try { action();fail("expected held settings access") } catch(expected:IllegalStateException) { assertTrue(expected.message!!.contains("설정 암호화 키")) } }
    @Test fun emptyReadCreatesNoKeyAndFirstSuccessfulSaveCreatesExactlyOne() {
        val keys=FixtureKeys();val settings=SecureSettings(context,keys)
        assertEquals(0,settings.snapshot().length());assertEquals(0,keys.creations)
        settings.update { it.put("treeUri","content://synthetic/folder");it.put("excludedRule","synthetic-opaque-group") }
        assertEquals(1,keys.creations);assertEquals("synthetic-opaque-group",settings.snapshot().getString("excludedRule"))
        settings.message("synthetic status");assertEquals(1,keys.creations)
    }
    @Test fun sealedSettingsWithMissingKeyNeitherGenerateNorOverwriteAnything() {
        val keys=FixtureKeys();val settings=SecureSettings(context,keys)
        settings.update { it.put("treeUri","content://synthetic/old");it.put("excludedRule","synthetic-opaque-group") }
        val before=sealed();val original=keys.value;keys.value=null
        rejects { settings.snapshot() };rejects { settings.update { it.put("treeUri","content://synthetic/new") } };rejects { settings.message("wrong success") }
        assertEquals(1,keys.creations);assertEquals(before,sealed());keys.value=original
        assertEquals("content://synthetic/old",settings.snapshot().getString("treeUri"));assertEquals("synthetic-opaque-group",settings.snapshot().getString("excludedRule"))
    }
    @Test fun existingUnreadableCiphertextNeverCreatesAnInitialKey() {
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().putString("state","malformed-synthetic-state").commit()
        val keys=FixtureKeys();val settings=SecureSettings(context,keys)
        try { settings.update { it.put("treeUri","new") };fail("must reject malformed ciphertext") } catch(expected:IllegalStateException) { }
        assertEquals(0,keys.creations);assertEquals("malformed-synthetic-state",sealed())
    }
    @Test fun wrongExistingKeyFailsAuthenticationWithoutReplacingSavedRules() {
        val keys=FixtureKeys();val settings=SecureSettings(context,keys)
        settings.update { it.put("excludedRule","synthetic-opaque-group") };val before=sealed()
        keys.value=SecretKeySpec(ByteArray(32) { 12 },"AES")
        try { settings.snapshot();fail("wrong key must fail") } catch(expected:javax.crypto.AEADBadTagException) { }
        assertEquals(1,keys.creations);assertEquals(before,sealed())
    }
}
