package kr.callbrief.navermail.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kr.callbrief.navermail.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}

/** Credentials and account metadata have separate Keystore keys and authenticated account/owner AAD. */
class NaverCredentialVault(context:Context,private val ownerId:String,
    private val policy:AccountActionPolicy=AccountActionPolicy.denied(),private val keys:KeyPort=AndroidKeys()) {
    interface KeyPort {
        fun existing(alias:String):SecretKey?
        fun create(alias:String,authenticationRequired:Boolean):SecretKey
        fun remove(alias:String)
    }
    class AndroidKeys:KeyPort {
        private fun store()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun existing(alias:String)=store().getKey(alias,null) as? SecretKey
        override fun create(alias:String,authenticationRequired:Boolean):SecretKey {
            check(existing(alias)==null)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(authenticationRequired)
                    .apply { if(authenticationRequired) {
                        if(android.os.Build.VERSION.SDK_INT>=30)setUserAuthenticationParameters(60,KeyProperties.AUTH_DEVICE_CREDENTIAL)
                        else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(60)
                    } }.build())
            }.generateKey()
        }
        override fun remove(alias:String) { store().deleteEntry(alias) }
    }
    data class SavedAccount(val account:AccountBinding?,val key:String,val scope:MailScope?,val state:ConnectionState,
        val error:MailError?=null,val lastVerifiedAt:Instant?=null)
    private val ownerHash=hash(ownerId)
    private val root=File(context.noBackupFilesDir,"naver-mail-v1/$ownerHash")
    init { require(ownerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) }
    private fun alias(key:String,purpose:String)="eum.naver.v1.$ownerHash.$key.$purpose"
    private fun dir(key:String):File { require(key.matches(Regex("[0-9a-f]{64}")));return File(root,key) }
    private fun aad(key:String,purpose:String)="naver-mail-v1\u0000$ownerId\u0000$key\u0000$purpose".toByteArray()
    private fun encrypt(key:String,purpose:String,bytes:ByteArray):ByteArray {
        val directory=dir(key)
        val secret=keys.existing(alias(key,purpose)) ?: if(File(directory,purpose).exists())throw MailFailure(MailError.STORAGE_KEY_LOST)
            else keys.create(alias(key,purpose),purpose=="credential")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,secret);updateAAD(aad(key,purpose)) }
        val sealed=cipher.doFinal(bytes)
        return byteArrayOf(1,cipher.iv.size.toByte())+cipher.iv+sealed
    }
    private fun decrypt(key:String,purpose:String):ByteArray {
        val secret=keys.existing(alias(key,purpose)) ?: throw MailFailure(MailError.STORAGE_KEY_LOST)
        val sealed=AtomicFile(File(dir(key),purpose)).readFully()
        if(sealed.size !in 30..131072 || sealed[0]!=1.toByte() || sealed[1]!=12.toByte())throw MailFailure(MailError.STORAGE_IO)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,secret,GCMParameterSpec(128,sealed.copyOfRange(2,14)));updateAAD(aad(key,purpose));doFinal(sealed,14,sealed.size-14)
        }
    }
    private fun atomicWrite(key:String,purpose:String,bytes:ByteArray) {
        val d=dir(key);check(d.exists() || d.mkdirs())
        val file=AtomicFile(File(d,purpose));val out=file.startWrite()
        try { out.write(bytes);file.finishWrite(out) }catch(e:Exception){file.failWrite(out);throw e}
    }
    /** Reading settings does not make directories or keys and never opens a socket. */
    @Synchronized fun accounts():List<SavedAccount> = root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) }?.take(20)?.map { d ->
        if(File(d,"revoked").exists())return@map SavedAccount(null,d.name,null,ConnectionState.DISCONNECTED)
        try {
            val bytes=decrypt(d.name,"metadata")
            val json=try { JSONObject(String(bytes,Charsets.UTF_8)) }finally{bytes.fill(0)}
            val account=AccountBinding(ownerId,json.getString("address"));require(account.key==d.name)
            val scope=parseScope(account,json.getJSONObject("scope"))
            // A past stored verification is not evidence of a current network connection.
            val state=if(File(d,"credential").exists())ConnectionState.VERIFY_REQUIRED else ConnectionState.REAUTH_REQUIRED
            SavedAccount(account,d.name,scope,state,lastVerifiedAt=json.optString("verifiedAt").takeIf{it.isNotBlank()}?.let(Instant::parse))
        }catch(e:Exception){ SavedAccount(null,d.name,null,ConnectionState.STORAGE_UNAVAILABLE,
            if(e is MailFailure)e.code else MailError.STORAGE_IO) }
    }?:emptyList()
    @Synchronized fun save(account:AccountBinding,scope:MailScope,secret:SecretChars,verifiedAt:Instant) {
        try {
            policy.requireApproved();require(account.ownerId==ownerId && scope.account==account)
            val key=account.key
            // A newly verified, explicitly authorized reconnect can erase a revoked local namespace.
            if(File(dir(key),"revoked").exists()) {
                for(p in listOf("metadata","credential","proposals")) {
                    keys.remove(alias(key,p));AtomicFile(File(dir(key),p)).delete()
                    if(File(dir(key),p).exists())throw MailFailure(MailError.STORAGE_IO)
                }
                if(!File(dir(key),"revoked").delete())throw MailFailure(MailError.STORAGE_IO)
                if(!dir(key).delete())throw MailFailure(MailError.STORAGE_IO)
            }
            // If a previous namespace exists, do not silently recreate either missing key.
            if(dir(key).exists())for(p in listOf("metadata","credential")) {
                if(keys.existing(alias(key,p))==null)throw MailFailure(MailError.STORAGE_KEY_LOST)
            }
            val encoded=secret.useChars { chars ->
                val buffer=Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars));ByteArray(buffer.remaining()).also { buffer.get(it);if(buffer.hasArray())buffer.array().fill(0) }
            }
            try { atomicWrite(key,"credential",encrypt(key,"credential",encoded)) }finally { encoded.fill(0) }
            val metadata=JSONObject().put("address",account.address).put("scope",scopeJson(scope)).put("verifiedAt",verifiedAt.toString()).toString().toByteArray()
            try { atomicWrite(key,"metadata",encrypt(key,"metadata",metadata)) }finally{metadata.fill(0)}
        }catch(e:MailFailure){throw e}catch(_:android.security.keystore.UserNotAuthenticatedException){throw MailFailure(MailError.AUTH_REQUIRED)}
        catch(_:Exception){throw MailFailure(MailError.STORAGE_IO)}finally{secret.close()}
    }
    @Synchronized fun credential(account:AccountBinding):SecretChars {
        policy.requireApproved();require(account.ownerId==ownerId)
        if(File(dir(account.key),"revoked").exists())throw MailFailure(MailError.CANCELLED)
        val bytes=try { decrypt(account.key,"credential") }catch(e:MailFailure){throw e}
        catch(_:android.security.keystore.UserNotAuthenticatedException){throw MailFailure(MailError.AUTH_REQUIRED)}
        catch(_:Exception){throw MailFailure(MailError.STORAGE_IO)}
        try { val chars=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));return SecretChars(CharArray(chars.remaining()).also { chars.get(it);if(chars.hasArray())chars.array().fill('\u0000') }) }
        finally{bytes.fill(0)}
    }
    /** Call MailIntake.disconnect first. Tombstone + key deletion makes late results/backup files unusable. */
    @Synchronized fun disconnect(key:String):Boolean {
        val d=dir(key);if(!d.exists())return true
        var complete=true
        try { AtomicFile(File(d,"revoked")).let { f -> val out=f.startWrite();out.write(1);f.finishWrite(out) } }catch(_:Exception){complete=false}
        for(p in listOf("credential","metadata","proposals")) {
            try { keys.remove(alias(key,p)) }catch(_:Exception){complete=false}
            val f=AtomicFile(File(d,p));f.delete();if(f.baseFile.exists())complete=false
        }
        // Tombstone stays; reconnect cannot implicitly restore an old namespace. Host must start an explicit new flow.
        return complete
    }
    companion object {
        fun scopeJson(s:MailScope)=JSONObject().put("caseId",s.caseId).put("counterpartId",s.counterpartId)
            .put("emails",org.json.JSONArray(s.correspondentAddresses.toList())).put("keywords",org.json.JSONArray(s.keywords.toList()))
            .put("since",s.since.toString()).put("before",s.before.toString()).put("limit",s.limit).put("id",s.id).put("revision",s.revision)
            .put("relationshipConfirmedByUser",s.relationshipConfirmedByUser)
        fun parseScope(a:AccountBinding,j:JSONObject):MailScope {
            fun values(k:String)=j.getJSONArray(k).let { arr -> (0 until arr.length()).map{arr.getString(it)}.toSet() }
            return MailScope(a,j.getString("caseId"),j.getString("counterpartId"),values("emails"),values("keywords"),LocalDate.parse(j.getString("since")),LocalDate.parse(j.getString("before")),limit=j.getInt("limit"),id=j.getString("id"),revision=j.getLong("revision"),relationshipConfirmedByUser=j.getBoolean("relationshipConfirmedByUser"))
        }
    }
}
