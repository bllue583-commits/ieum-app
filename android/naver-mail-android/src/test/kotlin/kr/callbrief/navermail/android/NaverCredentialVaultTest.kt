package kr.callbrief.navermail.android

import android.content.Context
import kr.callbrief.navermail.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class NaverCredentialVaultTest {
    private class TestKeys:NaverCredentialVault.KeyPort {
        val values=mutableMapOf<String,SecretKey>();val authentication=mutableMapOf<String,Boolean>();var creates=0
        override fun existing(alias:String)=values[alias]
        override fun create(alias:String,authenticationRequired:Boolean):SecretKey {creates++;authentication[alias]=authenticationRequired;return KeyGenerator.getInstance("AES").apply{init(256)}.generateKey().also{values[alias]=it}}
        override fun remove(alias:String){values.remove(alias)}
    }
    private val context:Context=org.robolectric.RuntimeEnvironment.getApplication()
    private fun owner()="fixture-"+UUID.randomUUID()
    private fun account(owner:String)=AccountBinding(owner,"fixture-mail@naver.com")
    private fun scope(a:AccountBinding)=MailScope(a,"fixture-case","fixture-person",setOf("other@example.invalid"),setOf("자료"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-08"),relationshipConfirmedByUser=true)
    private fun secret()=SecretChars(CharArray(16){('a'.code+it).toChar()})
    private fun save(v:NaverCredentialVault,a:AccountBinding){v.save(a,scope(a),secret(),Instant.parse("2026-10-12T00:00:00Z"))}
    private fun failCall(action:()->Unit):Exception{try{action();fail("Expected failure")}catch(e:Exception){return e};error("unreachable")}
    @Test fun freshReadCreatesNoFileOrKey(){val id=owner();val keys=TestKeys();val v=NaverCredentialVault(context,id,AccountActionPolicy.denied(),keys);assertTrue(v.accounts().isEmpty());assertEquals(0,keys.creates);assertFalse(File(context.noBackupFilesDir,"naver-mail-v1/${hash(id)}").exists())}
    @Test fun deniedSaveIsBlockedBeforeKeyCreationAndWipesInput(){val id=owner();val keys=TestKeys();val a=account(id);val v=NaverCredentialVault(context,id,AccountActionPolicy.denied(),keys);val b=CharArray(16){'x'};val e=failCall{v.save(a,scope(a),SecretChars(b),Instant.now())};assertEquals(MailError.APPROVAL_REQUIRED,(e as MailFailure).code);assertEquals(0,keys.creates);assertTrue(b.all{it=='\u0000'});assertTrue(v.accounts().isEmpty())}
    @Test fun storedCiphertextsExcludePlaintextAndCredentialKeyNeedsUnlock(){val id=owner();val keys=TestKeys();val a=account(id);val v=NaverCredentialVault(context,id,AccountActionPolicy.approvedByHost(),keys);save(v,a);val d=File(context.noBackupFilesDir,"naver-mail-v1/${hash(id)}/${a.key}");assertFalse(String(File(d,"metadata").readBytes()).contains(a.address));assertFalse(String(File(d,"credential").readBytes()).contains(CharArray(16){('a'.code+it).toChar()}.concatToString()));assertTrue(keys.authentication.filterKeys{it.endsWith("credential")}.values.single());assertEquals(ConnectionState.VERIFY_REQUIRED,v.accounts().single().state);v.credential(a).use{it.useChars{chars->assertEquals(16,chars.size)}}}
    @Test fun ownerAndAccountNamespacesRemainSeparate(){val first=owner();val second=owner();val keys=TestKeys();val one=NaverCredentialVault(context,first,AccountActionPolicy.approvedByHost(),keys);val two=NaverCredentialVault(context,second,AccountActionPolicy.approvedByHost(),keys);save(one,account(first));assertTrue(two.accounts().isEmpty());save(two,account(second));assertNotEquals(one.accounts().single().key,two.accounts().single().key);assertEquals(4,keys.creates)}
    @Test fun missingExistingMetadataKeyIsNotReplaced(){val id=owner();val keys=TestKeys();val a=account(id);val v=NaverCredentialVault(context,id,AccountActionPolicy.approvedByHost(),keys);save(v,a);keys.values.keys.filter{it.endsWith("metadata")}.toList().forEach{keys.values.remove(it)};val count=keys.creates;assertEquals(ConnectionState.STORAGE_UNAVAILABLE,v.accounts().single().state);failCall{save(v,a)};assertEquals(count,keys.creates)}
    @Test fun missingCredentialKeyIsNotReplaced(){val id=owner();val keys=TestKeys();val a=account(id);val v=NaverCredentialVault(context,id,AccountActionPolicy.approvedByHost(),keys);save(v,a);keys.values.keys.filter{it.endsWith("credential")}.toList().forEach{keys.values.remove(it)};val count=keys.creates;assertEquals(MailError.STORAGE_KEY_LOST,(failCall{v.credential(a)}as MailFailure).code);assertEquals(count,keys.creates)}
    @Test fun aadRejectsCrossAccountCiphertextSwap(){val id=owner();val keys=TestKeys();val a=account(id);val b=AccountBinding(id,"fixture-other@naver.com");val v=NaverCredentialVault(context,id,AccountActionPolicy.approvedByHost(),keys);save(v,a);save(v,b);val root=File(context.noBackupFilesDir,"naver-mail-v1/${hash(id)}");File(root,"${a.key}/credential").copyTo(File(root,"${b.key}/credential"),overwrite=true);assertEquals(MailError.STORAGE_IO,(failCall{v.credential(b)}as MailFailure).code)}
    @Test fun disconnectDeletesKeysAndBlocksCredentialReuse(){val id=owner();val keys=TestKeys();val a=account(id);val v=NaverCredentialVault(context,id,AccountActionPolicy.approvedByHost(),keys);save(v,a);assertTrue(v.disconnect(a.key));assertTrue(keys.values.isEmpty());assertEquals(ConnectionState.DISCONNECTED,v.accounts().single().state);assertEquals(MailError.CANCELLED,(failCall{v.credential(a)}as MailFailure).code)}
    @Test fun confirmedReconnectCreatesFreshKeysAfterRevocation(){val id=owner();val keys=TestKeys();val a=account(id);val v=NaverCredentialVault(context,id,AccountActionPolicy.approvedByHost(),keys);save(v,a);v.disconnect(a.key);save(v,a);assertEquals(4,keys.creates);assertEquals(ConnectionState.VERIFY_REQUIRED,v.accounts().single().state)}
}
