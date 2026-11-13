package kr.callbrief

import kr.callbrief.core.PrivacyRules
import kr.callbrief.pipeline.CallFollowupNotice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** v3.8 r18-lock (DESIGN_V7 4.2-5): the device salt for number fingerprints and the personal list moved from the code into the
 * settings, once. Audit: no stored fingerprint has its original value beside it on the phone (the personal list, the user's
 * exclusions, the follow-up notices), so none is re-made; the old form keeps matching next to the salted one.
 * Made-up numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class])
class NumberSaltTest : ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()

    @Test fun migrationKeepsTheRulesAndIsDoneOnce() {
        val s=JSONObject().put("personalAliasDisabled",true)
            .put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash("01000001111"))))
        val before=LocalPrivacy.rules(s)
        assertFalse(LocalPrivacy.heartbeat(s).getBoolean("saltedHash"))
        assertTrue(LocalPrivacy.migrateState(s))
        val salt=LocalPrivacy.salt(s)!!
        assertEquals(64,salt.length)
        assertEquals(LocalPrivacy.legacyPersonal().toString(),s.getJSONArray(LocalPrivacy.PERSONAL_KEY).toString())
        // Same rules as before (the flags still apply), now with the salt.
        val after=LocalPrivacy.rules(s)
        assertEquals(before.phones,after.phones);assertEquals(before.aliases,after.aliases);assertEquals(salt,after.salt)
        assertTrue(after.excluded("x","01000001111"))
        // Again: nothing changes, the salt is never replaced.
        assertFalse(LocalPrivacy.migrateState(s));assertEquals(salt,LocalPrivacy.salt(s))
        val h=LocalPrivacy.heartbeat(s)
        assertTrue(h.getBoolean("saltedHash"));assertEquals("two personal + one exclusion, all unsalted",3,h.getInt("legacyHashes"))
    }

    @Test fun theSettingsEndMigratedAndTheSaltStays() {
        // The app's own start may run the move at the same time (CallPipelineStartup): only the end state is asserted.
        val settings=SecureSettings(app)
        LocalPrivacy.migrate(settings)
        val s=settings.snapshot();val salt=LocalPrivacy.salt(s)
        assertNotNull(salt);assertTrue(LocalPrivacy.migrated(s))
        assertFalse(LocalPrivacy.migrate(settings))
        assertEquals(salt,LocalPrivacy.salt(settings.snapshot()))
    }

    @Test fun aMoveCutOffHalfwayIsFinishedNextTime() {
        // A salt but no list (as if only part had been written): the list is added, the salt kept.
        val s=JSONObject().put(LocalPrivacy.SALT_KEY,"5a".repeat(32))
        assertTrue(LocalPrivacy.migrateState(s))
        assertEquals("5a".repeat(32),LocalPrivacy.salt(s));assertTrue(s.has(LocalPrivacy.PERSONAL_KEY))
        // A bad salt value is replaced (nothing can have been fingerprinted with it).
        s.put(LocalPrivacy.SALT_KEY,"nope").remove(LocalPrivacy.MIGRATED_KEY)
        assertTrue(LocalPrivacy.migrateState(s));assertNotNull(LocalPrivacy.salt(s))
    }

    @Test fun personalListBeforeAndAfterTheMoveIsTheSame() {
        val plain=JSONObject()
        val moved=JSONObject().put(LocalPrivacy.PERSONAL_KEY,LocalPrivacy.legacyPersonal()).put(LocalPrivacy.SALT_KEY,"11".repeat(32))
        assertEquals(LocalPrivacy.rules(plain).phones,LocalPrivacy.rules(moved).phones)
        assertEquals(LocalPrivacy.rules(plain).aliases,LocalPrivacy.rules(moved).aliases)
        assertTrue(LocalPrivacy.rules(plain).phones.contains(LocalPrivacy.PERSONAL_PHONE_HASH))
        val off=JSONObject(moved.toString()).put("personalPhoneDisabled",true).put("personalAliasDisabled",true)
        assertTrue(LocalPrivacy.rules(off).phones.isEmpty());assertTrue(LocalPrivacy.rules(off).aliases.isEmpty())
    }

    @Test fun newFingerprintsAreSaltedOldOnesStillMatch() {
        val key="01000004444";val salted=JSONObject().put(LocalPrivacy.SALT_KEY,"22".repeat(32))
        val h=LocalPrivacy.numberHash(salted,key)
        assertEquals(PrivacyRules.salted("22".repeat(32),key),h);assertNotEquals(PrivacyRules.hash(key),h)
        assertTrue(LocalPrivacy.sameNumber(salted,h,key))
        assertTrue("a notice written before the salt",LocalPrivacy.sameNumber(salted,PrivacyRules.hash(key),key))
        assertFalse(LocalPrivacy.sameNumber(salted,h,"01000005555"))
        assertEquals("no salt yet: the old form",PrivacyRules.hash(key),LocalPrivacy.numberHash(JSONObject(),key))
        // Follow-up notice matching takes the comparison it is given.
        val e=CallFollowupNotice.Entry(1,"가상 대표",null,h,1_000_000,1_060_000)
        val record=kr.callbrief.pipeline.CallRecord("r","rec",1_010_000,60_000,null,key,"",kr.callbrief.pipeline.CallRecordStore.ANALYZED,null,null,emptyList(),null,0,null)
        assertNull("old comparison misses a salted entry",CallFollowupNotice.match(listOf(e),record))
        assertEquals(e,CallFollowupNotice.match(listOf(e),record) { a,b -> LocalPrivacy.sameNumber(salted,a,b) })
    }
}
