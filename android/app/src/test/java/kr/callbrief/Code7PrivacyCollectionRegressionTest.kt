package kr.callbrief

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import kr.callbrief.core.PrivacyRules
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Actual selected-source adapter with a generated provider/grant shadow. Not real device SAF. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Code7PrivacyCollectionRegressionTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var settings:SecureSettings
    private lateinit var provider:Code6FollowupRegressionTest.Provider
    private lateinit var source:SafSelectedRecordingSource
    private val tree=DocumentsContract.buildTreeDocumentUri("privacy.synthetic.callbrief","root")
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=try { executor.submit<T> { f() }.get(20,TimeUnit.SECONDS) }
        catch(error:java.util.concurrent.ExecutionException) { throw error.cause!! }
    @Before fun onlyGeneratedRulesAndProvider() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys()
        context.deleteDatabase(GeneralLocalStore.DATABASE);File(context.noBackupFilesDir,"local-recordings-v2").deleteRecursively()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        val key=SecretKeySpec(ByteArray(32) { 29 },"AES")
        settings=SecureSettings(context,object:SecureSettings.KeyAccess {
            override fun existing():SecretKey=key
            override fun createForFirstEmptyStore():SecretKey=error("NO_TEST_KEY_GENERATION")
        })
        settings.update { it.put("treeUri",tree.toString());it.put("personalPhoneDisabled",true);it.put("personalAliasDisabled",true) }
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider=Code6FollowupRegressionTest.Provider();provider.attachInfo(context,ProviderInfo().apply { authority="privacy.synthetic.callbrief";grantUriPermissions=true })
        ShadowContentResolver.registerProviderInternal("privacy.synthetic.callbrief",provider)
        source=SafSelectedRecordingSource(context,settings,tree.toString())
        io { GeneralLocalStoreTest.host(context,keys).use { it.initializeEmpty() } }
    }
    @After fun cleanup() { executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun phoneRule(value:String)=JSONObject().put("type","phone").put("hash",PrivacyRules.hash(PrivacyRules.normalizePhone(value)))
    private fun aliasRule(value:String)=JSONObject().put("type","alias").put("hash",PrivacyRules.hash(PrivacyRules.normalizeAlias(value))).put("length",PrivacyRules.normalizeAlias(value).length)
    private fun rules(vararg values:JSONObject) { settings.update { it.put("exclusions",JSONArray(values.toList())) } }
    private fun put(name:String):SourceDocument {
        val d=SourceDocument("one",DocumentsContract.buildDocumentUriUsingTree(tree,"one").toString(),name,52,"audio/wav",GeneralLocalStoreTest.NOW-600000)
        provider.documents["one"]=d;return d
    }
    private fun file(value:String)="통화 녹음_${value}_20261010_100000.wav"
    private fun report(selected:SelectedRecordingSource=source)=io { GeneralLocalStoreTest.host(context,keys).use { it.importSelected(TextStoreTestSupport.generatedSource(selected)) } }
    private fun rows()=io { GeneralLocalStoreTest.host(context,keys).use { it.snapshot() } }
    private fun intakes()=io { AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { it.query("SELECT * FROM local_intakes").size } }
    private fun ref(d:SourceDocument)=RecordingRef(Uri.parse(d.uri),d.name,d.size,d.mime,null,d.modified)
    private fun evidence(id:String,vararg values:Pair<String,Any?>) {
        val data=JSONObject().put("id",id).put("acceptance_pass",true).put("scope","HOST_PROVIDER_GRANT_SHADOW_PUBLIC_JCE_SYNTHETIC_RULES_ONLY")
        values.forEach { data.put(it.first,it.second ?: JSONObject.NULL) }
        val dir=File("build/reports/code7-privacy").apply { mkdirs() };File(dir,"$id.json").writeText(data.toString(2))
    }
    @Test fun F01CoincidentalShortSubstringAllowsDifferentCompleteName() {
        rules(aliasRule("민솔"));put(file("시민솔루션 01000002222"));val r=report()
        assertEquals(1,r.newVersions);assertEquals(0,r.excluded);assertEquals(0,r.held);assertEquals(2,provider.opens);assertEquals(1,rows().length())
        evidence("F01","new_versions" to 1,"excluded" to 0,"held" to 0,"provider_opens" to 2,"unrelated_full_name_preserved" to true)
    }
    @Test fun F02CompleteExcludedAliasHasZeroOpensAndIntakes() {
        rules(aliasRule("민솔"));put(file("민솔"));val r=report()
        assertEquals(1,r.excluded);assertEquals(0,provider.opens);assertEquals(0,rows().length());assertEquals(0,intakes())
        evidence("F02","excluded" to 1,"provider_opens" to 0,"recordings" to 0,"intakes" to 0,"all_database_rows_claim" to false)
    }
    @Test fun F03UnknownFormatHoldsBeforeHashAndCanonicalCopy() {
        put("일반 자료.wav");val r=report()
        assertEquals(1,r.held);assertEquals(0,r.excluded);assertEquals(0,provider.opens);assertEquals(0,rows().length());assertEquals(0,intakes())
        evidence("F03","held" to 1,"provider_opens" to 0,"recordings" to 0,"intakes" to 0,"audio_hash_or_copy" to false)
    }
    @Test fun F04SavedApprovalCannotBypassUnknownFilenameBoundary() {
        val d=put("일반 자료.wav")
        settings.update { it.put("identities",JSONObject().put(d.uri,JSONObject().put("phone","01000002222").put("alias","가상기관"))) }
        assertEquals(LocalCollectionDecision.HOLD,LocalPrivacy.collectionDecision(ref(d),settings.snapshot()))
        val r=report();assertEquals(1,r.held);assertEquals(0,provider.opens)
        evidence("F04","saved_approval_bypassed" to false,"held" to 1,"provider_opens" to 0)
    }
    @Test fun F05AnyExcludedExactPhoneWinsOverAmbiguousMultiplePhones() {
        rules(phoneRule("01000001111"));put(file("01000001111 01000002222"));val r=report()
        assertEquals(1,r.excluded);assertEquals(0,provider.opens)
        evidence("F05","excluded" to 1,"provider_opens" to 0,"multiple_phone_auto_identity" to false)
    }
    @Test fun F06InternationalSpaceSeparatorAndFullWidthExcludedPhonesNeverOpen() {
        rules(phoneRule("01000001111"))
        for(value in listOf("+82 10 0000 1111","0082 (0)10 0000 1111","010_0000_1111","０１０００００１１１１")) {
            val d=put(file(value));assertTrue(source.excluded(d))
            assertThrows(IllegalStateException::class.java) { source.open(d) }
        }
        assertEquals(0,provider.opens)
        evidence("F06","provider_opens" to 0,"phone_formats" to 4,"fixtures_synthetic" to true)
    }
    @Test fun F07DifferentVerifiedPhoneCollectsWithoutPersonOrCaseAssignment() {
        rules(phoneRule("01000001111"));put(file("01000002222"));val r=report();assertEquals(1,r.newVersions)
        val row=rows().getJSONObject(0);assertTrue(row.isNull("person_id"));assertTrue(row.isNull("case_id"));assertTrue(row.isNull("original_date"))
        evidence("F07","new_versions" to 1,"person_assignment" to false,"case_assignment" to false,"original_date_guessed" to false)
    }
    @Test fun F08EmojiAndPunctuationCannotBypassWholeExcludedAlias() {
        rules(aliasRule("민솔"));put(file("민·솔~~🌷"));val r=report();assertEquals(1,r.excluded);assertEquals(0,provider.opens)
        evidence("F08","excluded" to 1,"provider_opens" to 0,"whole_alias_normalization" to true)
    }
    @Test fun F09PolicyChangeAfterOpenBlocksBeforeFirstStreamRead() {
        val d=put(file("가상기관 01000002222"));var bytesRead=0
        val selected=object:SelectedRecordingSource by source {
            override fun open(document:SourceDocument):InputStream {
                val input=source.open(document);rules(aliasRule("가상기관"))
                return object:FilterInputStream(input) {
                    override fun read(b:ByteArray,o:Int,n:Int):Int = `in`.read(b,o,n).also { if(it>0) bytesRead+=it }
                }
            }
        }
        val r=report(selected);assertEquals(1,r.held);assertEquals(1,provider.opens);assertEquals(0,bytesRead);assertEquals(0,rows().length());assertEquals(0,intakes())
        assertTrue(source.excluded(d))
        evidence("F09","policy_changed_after_handle_open" to true,"provider_opens" to 1,"plaintext_bytes_read" to 0,"recordings" to 0,"intakes" to 0,"atomic_revoke_network_claim" to false)
    }
    @Test fun F10AmbiguousNonExcludedPhonesAreHeldWithoutOpening() {
        put(file("01000002222 01000003333"));val r=report();assertEquals(1,r.held);assertEquals(0,provider.opens)
        evidence("F10","held" to 1,"provider_opens" to 0,"ambiguous_identity_allowed" to false)
    }
    @Test fun F11LetterEmbeddedNumberCannotBeReadAsVerifiedPhone() {
        put(file("문자01000002222문자"));val r=report();assertEquals(1,r.held);assertEquals(0,provider.opens)
        evidence("F11","held" to 1,"provider_opens" to 0,"discard_letters_to_approve" to false)
    }
    @Test fun F12KnownRecorderNameWithoutTimestampRetainsUnknownOriginalTime() {
        put("통화 녹음_가상기관🌻 01000002222.wav");val r=report();assertEquals(1,r.newVersions);assertEquals(2,provider.opens)
        val row=rows().getJSONObject(0);assertTrue(row.isNull("original_date"));assertTrue(row.isNull("filename_time_candidate"))
        evidence("F12","new_versions" to 1,"original_time_unknown" to true,"filename_time_candidate" to false,"person_assignment" to false)
    }
    @Test fun F13ParsedNameWithoutVerifiedPhoneHoldsBeforeAnyResolverOpen() {
        rules(aliasRule("민솔"));val d=put(file("시민솔루션"))
        assertFalse(source.excluded(d));assertEquals(LocalCollectionDecision.HOLD,LocalPrivacy.collectionDecision(ref(d),settings.snapshot()))
        val r=report();assertEquals(1,r.held);assertEquals(0,provider.opens);assertEquals(0,rows().length());assertEquals(0,intakes())
        evidence("F13","full_name_not_excluded" to true,"counterpart_phone_unconfirmed" to true,"held" to 1,"provider_opens" to 0,"intakes" to 0)
    }
    @Test fun F14ExistingScopedExplicitAliasConfirmationCanPermitSyntheticRead() {
        val d=put(file("가상기관"));val state=settings.snapshot();val key=LocalPrivacy.mappingKey(ref(d),state)!!
        settings.update { it.put("aliasMappings",JSONObject().put(key,JSONObject().put("treeUri",tree.toString()).put("phone","01000002222")
            .put("display_alias","가상기관").put("reuse_enabled",true).put("conflict",false))) }
        assertEquals(LocalCollectionDecision.ALLOW,LocalPrivacy.collectionDecision(ref(d),settings.snapshot()))
        val r=report();assertEquals(1,r.newVersions);assertEquals(2,provider.opens)
        assertTrue(rows().getJSONObject(0).isNull("person_id"));assertTrue(rows().getJSONObject(0).isNull("case_id"))
        evidence("F14","existing_explicit_scoped_confirmation" to true,"new_versions" to 1,"provider_opens" to 2,"person_or_case_auto_assignment" to false,"new_confirmation_ui_claim" to false)
    }
    @Test fun F15SourceWithoutExplicitIdentityPolicyCannotUsePermissiveDefault() {
        put(file("01000002222"))
        val unattested=object:SelectedRecordingSource {
            override val rootUri=source.rootUri
            override fun checkSelected()=source.checkSelected()
            override fun inventory()=source.inventory()
            override fun stat(document:SourceDocument)=source.stat(document)
            override fun excluded(document:SourceDocument)=source.excluded(document)
            override fun open(document:SourceDocument)=source.open(document)
        }
        val r=report(unattested);assertEquals(1,r.held);assertEquals(0,provider.opens);assertEquals(0,intakes())
        evidence("F15","source_policy_unattested" to true,"held" to 1,"provider_opens" to 0,"intakes" to 0)
    }
    @Test fun F16ContradictorySavedIdentityHoldsBeforeAnyResolverOpen() {
        val d=put(file("01000002222"))
        settings.update { it.put("identities",JSONObject().put(d.uri,JSONObject().put("phone","01000003333").put("alias","가상기관"))) }
        assertEquals(LocalCollectionDecision.HOLD,LocalPrivacy.collectionDecision(ref(d),settings.snapshot()))
        val r=report();assertEquals(1,r.held);assertEquals(0,provider.opens);assertEquals(0,intakes())
        evidence("F16","saved_identity_conflict" to true,"held" to 1,"provider_opens" to 0,"intakes" to 0)
    }
    @Test fun F17LegacyUriOnlyEntryCannotApproveUnnumberedNewFilename() {
        val d=put(file("가상기관"))
        settings.update { it.put("identities",JSONObject().put(d.uri,JSONObject().put("phone","01000002222").put("alias","가상기관"))) }
        assertNotNull(LocalPrivacy.identity(ref(d),settings.snapshot()))
        assertEquals(LocalCollectionDecision.HOLD,LocalPrivacy.collectionDecision(ref(d),settings.snapshot()))
        val r=report();assertEquals(1,r.held);assertEquals(0,provider.opens);assertEquals(0,intakes())
        evidence("F17","legacy_uri_only_approval" to false,"held" to 1,"provider_opens" to 0,"intakes" to 0,"existing_entry_overwritten" to false)
    }
}
