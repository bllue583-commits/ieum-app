package kr.callbrief

import android.net.Uri
import kr.callbrief.core.PrivacyRules
import kr.callbrief.core.RecordingNames
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Entirely fabricated identities. No recordings are read and no network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class])
class LocalPrivacyTest : ProtectedTextHostTestBase() {
    private fun record(name:String)=RecordingRef(Uri.parse("content://synthetic/call"),name,100,"audio/mp4",RecordingNames.startedAt(name),1)
    private fun state()=JSONObject().put("treeUri","content://synthetic/tree/test")
    private fun mapping(record:RecordingRef,state:JSONObject,phone:String="01000001111") {
        state.put("aliasMappings",JSONObject().put(LocalPrivacy.mappingKey(record,state)!!,JSONObject()
            .put("treeUri",state.getString("treeUri")).put("phone",phone).put("display_alias","가상업체")
            .put("reuse_enabled",true).put("conflict",false).put("case_id","synthetic-topic")))
    }
    @Test fun unresolvedNamesFailClosedEvenWithTimeAndGroupAssignment() {
        val record=record("통화 녹음 가상업체_261009_112233.m4a");val state=state()
        state.put("assignments",JSONObject().put(record.uri.toString(),"synthetic-topic"))
        state.put("timeOverrides",JSONObject().put(record.uri.toString(),"2026-10-09T02:22:33Z"))
        assertNull(LocalPrivacy.identity(record,state))
        assertThrows(IllegalArgumentException::class.java) { LocalPrivacy.requireAllowed(record,state) }
    }
    @Test fun confirmedAliasReuseIsOptInAndFolderScoped() {
        val record=record("통화 녹음 가상업체_261009_112233.m4a");val state=state();mapping(record,state)
        assertEquals("01000001111",LocalPrivacy.requireAllowed(record,state).phone)
        assertEquals("synthetic-topic",LocalPrivacy.reusedCase(record,state))
        state.put("treeUri","content://synthetic/tree/another")
        assertNull(LocalPrivacy.identity(record,state))
    }
    @Test fun collisionAndRevokedReuseRemainBlocked() {
        val record=record("통화 녹음 가상업체_261009_112233.m4a");val state=state();mapping(record,state)
        val entry=state.getJSONObject("aliasMappings").getJSONObject(LocalPrivacy.mappingKey(record,state)!!)
        entry.put("conflict",true);assertNull(LocalPrivacy.identity(record,state))
        entry.put("conflict",false).put("reuse_enabled",false);assertNull(LocalPrivacy.identity(record,state))
        assertNull(LocalPrivacy.reusedCase(record,state))
    }
    @Test fun excludedPhoneAndAliasOverrideSavedApproval() {
        val record=record("통화 녹음 가상업체_261009_112233.m4a");val state=state();mapping(record,state)
        state.put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash("01000001111"))))
        assertTrue(LocalPrivacy.blocked(record,state))
        assertThrows(IllegalArgumentException::class.java) { LocalPrivacy.requireAllowed(record,state) }
        state.put("exclusions",JSONArray().put(JSONObject().put("type","alias").put("hash",PrivacyRules.hash("가상업체")).put("length",4)))
        assertTrue(LocalPrivacy.blocked(record,state))
    }
    @Test fun contradictoryOrMultipleExplicitPhonesCannotUseOldApproval() {
        val record=record("통화 녹음 가상업체 01000002222 01000003333_261009_112233.m4a");val state=state()
        state.put("identities",JSONObject().put(record.uri.toString(),JSONObject().put("phone","01000002222").put("alias","가상업체")))
        assertNull(LocalPrivacy.identity(record,state))
        val changed=record("통화 녹음 가상업체 01000003333_261009_112233.m4a")
        assertNull(LocalPrivacy.identity(changed,state))
    }
    @Test fun identityReusePreservesEmojiAndPunctuationUnlikeExclusionMatching() {
        val heart=record("통화 녹음 가상업체💛_261009_112233.m4a")
        val briefcase=record("통화 녹음 가상업체💼_261009_112233.m4a")
        val punctuation=record("통화 녹음 가상업체~~_261009_112233.m4a")
        val dash=record("통화 녹음 가상업체-_261009_112233.m4a")
        val plain=record("통화 녹음 가상업체_261009_112233.m4a")
        val wide=record("통화 녹음 Ａ회사_261009_112233.m4a")
        val latin=record("통화 녹음 A회사_261009_112233.m4a")
        val state=state();mapping(heart,state)
        assertNotEquals(LocalPrivacy.mappingKey(heart,state),LocalPrivacy.mappingKey(briefcase,state))
        assertNotEquals(LocalPrivacy.mappingKey(heart,state),LocalPrivacy.mappingKey(punctuation,state))
        assertNotEquals(LocalPrivacy.mappingKey(dash,state),LocalPrivacy.mappingKey(plain,state))
        assertNotEquals(LocalPrivacy.mappingKey(wide,state),LocalPrivacy.mappingKey(latin,state))
        assertNull(LocalPrivacy.identity(briefcase,state));assertNull(LocalPrivacy.identity(punctuation,state))
        assertEquals("01000001111",LocalPrivacy.requireAllowed(heart,state).phone)
    }
    @Test fun multipleNumbersCannotFallThroughToAliasReuse() {
        val record=record("통화 녹음 가상업체 01000002222 01000003333_261009_112233.m4a")
        val state=state();mapping(record,state)
        assertNull(LocalPrivacy.identity(record,state))
        assertThrows(IllegalArgumentException::class.java) { LocalPrivacy.requireAllowed(record,state) }
    }
}
