package kr.callbrief

import android.content.Context
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import java.util.UUID

/** Base-app (not Google-owner) identity vault. ProtectedDatabase supplies AEAD, AtomicFile and an
 * exclusive OS file lock; another process cannot race a refresh with a stale credential snapshot.
 * No global singleton is installed. Construct on IO, and keep alive for the login/runtime lifecycle. */
internal class SiwcSessionStore(context:Context,private val transport:SiwcAuthTransport,private val now:()->Long=System::currentTimeMillis,
    private val refreshNotBefore:(Any?)->Long?={if(it==null)0L else null}):AutoCloseable {
    init {require(context !is AccountStorageContext) { "SIWC_USE_BASE_APP_CONTEXT" }}
    private val store=ProtectedDatabase.open(context,"siwc-auth-v1.db",SCHEMA)
    private val db=store.database()
    private var cached:Pair<String,SiwcRuntimeGrant>?=null
    init {store.transaction {
        db.execSQL("INSERT OR IGNORE INTO siwc_auth_meta VALUES(1,?,'',0,0,?)",arrayOf("urn:uuid:"+UUID.randomUUID(),PROCESS))
        db.execSQL("UPDATE siwc_auth_meta SET pending=pending+1,process=? WHERE process!=?",arrayOf(PROCESS,PROCESS))
        db.execSQL("UPDATE siwc_auth_accounts SET state='REFRESH_UNKNOWN',status='SIWC_REFRESH_INTERRUPTED' WHERE state='REFRESHING' AND process!=?",arrayOf(PROCESS))
        db.execSQL("UPDATE siwc_auth_accounts SET record=NULL,state='SIGNED_OUT',status='SIWC_REMOTE_REVOCATION_UNKNOWN' WHERE state='SIGNING_OUT' AND process!=?",arrayOf(PROCESS));null
    }}
    class Registration(val key:String,val clientId:String,val subject:String,val email:String,val state:String,val status:String) {
        override fun toString()="SiwcRegistration[withheld]"
    }
    private class Tokens(val access:String,val refresh:String,val idToken:String,val scopes:Set<String>,val expiresMs:Long,
        val refreshExpiresMs:Long,val earliestRaw:Any?,val nonce:String) {
        fun bytes()=StrictJson.bytes(mapOf("access" to access,"refresh" to refresh,"id" to idToken,"scopes" to scopes.sorted(),"expires" to expiresMs,
            "refreshExpires" to refreshExpiresMs,"earliest" to earliestRaw,"nonce" to nonce))
        override fun toString()="SiwcTokens[withheld]"
        companion object {fun read(bytes:ByteArray):Tokens {val x=AstraAudioContract.parse(bytes);return Tokens(x["access"] as String,x["refresh"] as String,x["id"] as String,
            (x["scopes"] as List<*>).map{it as String}.toSet(),x["expires"] as Long,x["refreshExpires"] as Long,x["earliest"],x["nonce"] as String)}}
    }
    private fun meta()=ProtectedDatabase.read(db) {db.rawQuery("SELECT host,active,epoch,pending FROM siwc_auth_meta WHERE id=1",null).use{c->check(c.moveToFirst());listOf(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3))}}
    fun hostId()=meta()[0] as String
    fun epoch()=meta()[2] as Long
    fun activeKey()=meta()[1] as String
    fun registrations():List<Registration> = ProtectedDatabase.read(db) {db.rawQuery("SELECT id,client,subject,email,state,status FROM siwc_auth_accounts ORDER BY id",null).use{c->buildList{while(c.moveToNext())add(Registration(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)))}}}
    private fun registration(key:String)=registrations().single{it.key==key}
    private fun tokens(key:String):Tokens?=ProtectedDatabase.read(db) {db.rawQuery("SELECT record FROM siwc_auth_accounts WHERE id=?",arrayOf(key)).use{c->if(c.moveToFirst() && !c.isNull(0))Tokens.read(c.getBlob(0)) else null}}
    fun pendingCurrent(p:SiwcOAuthProtocol.Pending)=meta()[3]==p.epoch && now()>=p.createdMs && now()-p.createdMs<300000
    fun begin(config:SiwcOAuthConfig?,redirect:String,selectedKey:String?,requestPlan:Boolean):SiwcOAuthProtocol.Pending {
        val c=config ?: error("SIWC_AUTH_NOT_CONFIGURED")
        return store.transaction {
            val selected=selectedKey?.let(::registration)
            check(selected==null || selected.clientId==c.issuedClientId) { "SIWC_SELECTED_CLIENT_CHANGED" }
            db.execSQL("UPDATE siwc_auth_meta SET pending=pending+1 WHERE id=1")
            SiwcOAuthProtocol.Pending(c,hostId(),redirect,requestPlan,selected?.subject,meta()[3] as Long,now())
        }
    }
    fun cancel(p:SiwcOAuthProtocol.Pending)=store.transaction {
        if(meta()[3]==p.epoch)db.execSQL("UPDATE siwc_auth_meta SET pending=pending+1 WHERE id=1");null
    }
    /** Preserve an issued registration ID even if its one-time code later fails; no account activation. */
    fun rememberIssued(p:SiwcOAuthProtocol.Pending,id:String)=store.transaction {
        check(pendingCurrent(p)) { "SIWC_LOGIN_STALE" };SiwcOAuthProtocol.clientId(id)
        if(registrations().none{it.clientId==id})db.execSQL("INSERT OR IGNORE INTO siwc_auth_drafts VALUES(?)",arrayOf(id));null
    }
    fun incompleteRegistrationIds():List<String> = ProtectedDatabase.read(db) {db.rawQuery("SELECT client FROM siwc_auth_drafts ORDER BY client",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}}
    private fun tokenResponse(raw:ByteArray,client:String,nonce:String,selected:String?,keys:ByteArray,code:String?,receivedMs:Long,old:Tokens?=null):Pair<SiwcOAuthProtocol.Identity?,Tokens> {
        val x=AstraAudioContract.parse(raw);check(x["token_type"]=="Bearer") { "SIWC_TOKEN_TYPE" }
        fun credential(name:String,required:Boolean=true):String {val v=x[name] as? String ?: if(required)error("SIWC_TOKEN_FIELD") else return ""
            check(v.length in 1..65536 && v.all{it.code in 33..126}) { "SIWC_TOKEN_FIELD" };return v}
        val access=credential("access_token");val scopes=(x["scope"] as? String)?.split(' ')?.filter{it.isNotEmpty()}?.toSet() ?: old?.scopes ?: error("SIWC_GRANTED_SCOPES_REQUIRED")
        check("openid" in scopes) { "SIWC_IDENTITY_SCOPE_REQUIRED" }
        val refresh=credential("refresh_token","offline_access" in scopes)
        check("offline_access" in scopes || refresh.isBlank()) { "SIWC_UNGRANTED_REFRESH_TOKEN" }
        val id=credential("id_token",old==null)
        val identity=if(id.isNotBlank())SiwcOAuthProtocol.identity(id,keys,client,nonce,now(),selected,access,code,old==null) else null
        val seconds=AstraAudioContract.number(x,"expires_in");check(seconds in 1..86400) { "SIWC_TOKEN_EXPIRY" }
        // Published docs name this field but do not specify its encoding/units. Preserve it;
        // absent = normal expiry scheduling; present requires an integration-verified decoder.
        val earliest=x["earliest_refresh_at"];check(earliest==null || earliest is Long && earliest>=0 || earliest is String && earliest.length<=128) { "SIWC_REFRESH_SCHEDULE_SHAPE" }
        val tokens=Tokens(access,refresh,id.ifBlank{checkNotNull(old).idToken},scopes,Math.addExact(receivedMs,seconds*1000),
            if(refresh.isBlank())0 else receivedMs+30L*24*60*60*1000,earliest,nonce)
        return identity to tokens
    }
    /** Token response + issuer keys are trusted transport observations, never callback token fields. */
    fun finish(p:SiwcOAuthProtocol.Pending,code:SiwcOAuthProtocol.Code,metadata:SiwcOAuthProtocol.Metadata):Registration {
        check(pendingCurrent(p)) { "SIWC_LOGIN_STALE" };val form=p.exchange(code);rememberIssued(p,code.clientId)
        val raw=transport.token(form);val receivedMs=now()
        check(pendingCurrent(p)) { "SIWC_LOGIN_STALE" }
        val (identity,tokens)=tokenResponse(raw,code.clientId,p.nonce,p.selectedSubject,transport.jwks(metadata),code.value,receivedMs)
        checkNotNull(identity);check(tokens.scopes.all{it in p.requestedScopes}) { "SIWC_UNREQUESTED_GRANT" }
        val key=AstraAudioContract.sha(StrictJson.bytes(listOf(SiwcOAuthProtocol.ISSUER,code.clientId,identity.subject)))
        store.transaction {
            check(pendingCurrent(p)) { "SIWC_LOGIN_STALE" }
            // An issued registration cannot silently change identity, even if no picker key was supplied.
            check(registrations().none{it.clientId==code.clientId && it.subject!=identity.subject}) { "SIWC_ACCOUNT_CHANGED" }
            db.execSQL("INSERT OR REPLACE INTO siwc_auth_accounts VALUES(?,?,?,?,?,'ACTIVE',?,'',0)",arrayOf(key,code.clientId,identity.subject,identity.email.orEmpty(),tokens.bytes(),PROCESS))
            db.execSQL("DELETE FROM siwc_auth_drafts WHERE client=?",arrayOf(code.clientId))
            db.execSQL("UPDATE siwc_auth_meta SET active=?,epoch=epoch+1,pending=pending+1 WHERE id=1",arrayOf(key));null
        };cached=null;return registration(key)
    }
    /** Caller retains this epoch with every source/revision binding. Authentication != transfer consent. */
    fun runtimeScope(ownerScope:String):String=ProtectedDatabase.read(db) {
        require(ownerScope.isNotBlank()) { "SIWC_OWNER_SCOPE_REQUIRED" }
        val m=meta();val key=m[1] as String;check(key.isNotBlank() && registration(key).state=="ACTIVE") { "SIWC_SIGN_IN_REQUIRED" }
        AstraAudioContract.sha(StrictJson.bytes(listOf(ownerScope,key,m[2])))
    }
    @Synchronized fun inferenceGrant(ownerScope:String,approvedKeys:Set<String>):SiwcRuntimeGrant?=ProtectedDatabase.read(db) {
        val key=activeKey();if(key.isBlank() || registration(key).state!="ACTIVE")return@read null
        val t=tokens(key) ?: return@read null
        if(!t.scopes.containsAll(setOf("resource.invoke","chatgpt.tokens.use.direct")) || t.expiresMs<=now())return@read null
        val scope=runtimeScope(ownerScope);val fingerprint=AstraAudioContract.sha(StrictJson.bytes(listOf(scope,t.access,t.expiresMs,approvedKeys.sorted())))
        if(cached?.first!=fingerprint)cached=fingerprint to SiwcRuntimeGrant(scope,t.access,t.expiresMs,true,approvedKeys)
        cached!!.second
    }
    /** Rotate once. Claim is durable before sending; interrupted/unknown exchanges cannot retry the
     * old refresh token. Explicit 5xx retains credentials with backoff; terminal codes clear secrets. */
    fun refresh(key:String):String {
        val claim=store.transaction {
            val r=registration(key);val t=tokens(key)
            if(r.state!="ACTIVE" || t==null || activeKey()!=key)return@transaction null
            if(t.expiresMs-now()>120000)return@transaction null
            val earliest=refreshNotBefore(t.earliestRaw)
            if(earliest==null) {db.execSQL("UPDATE siwc_auth_accounts SET status='SIWC_REFRESH_SCHEDULE_UNVERIFIED' WHERE id=?",arrayOf(key));return@transaction null}
            check(earliest>=0) { "SIWC_REFRESH_SCHEDULE_SHAPE" };if(now()<earliest)return@transaction null
            if(t.refresh.isBlank() || t.refreshExpiresMs<=now()) {clear(key,"SIWC_REAUTH_REQUIRED");return@transaction null}
            val next=ProtectedDatabase.read(db){db.rawQuery("SELECT next_ms FROM siwc_auth_accounts WHERE id=?",arrayOf(key)).use{it.moveToFirst();it.getLong(0)}}
            if(now()<next)return@transaction null
            db.execSQL("UPDATE siwc_auth_accounts SET state='REFRESHING',process=? WHERE id=?",arrayOf(PROCESS,key))
            Triple(r,t,epoch())
        } ?: return registration(key).state
        cached=null
        try {
            val (r,old,expectedEpoch)=claim
            val metadata=transport.metadata();val raw=transport.token(mapOf("grant_type" to "refresh_token","client_id" to r.clientId,"refresh_token" to old.refresh,"resource" to SiwcOAuthProtocol.RESOURCE));val receivedMs=now()
            val (_,replacement)=tokenResponse(raw,r.clientId,old.nonce,r.subject,transport.jwks(metadata),null,receivedMs,old)
            check(replacement.scopes.all{it in old.scopes}) { "SIWC_UNREQUESTED_GRANT" }
            store.transaction {
                check(registration(key).state=="REFRESHING" && epoch()==expectedEpoch && activeKey()==key) { "SIWC_REFRESH_STALE" }
                db.execSQL("UPDATE siwc_auth_accounts SET record=?,state='ACTIVE',status='',next_ms=0 WHERE id=?",arrayOf(replacement.bytes(),key));null
            }
        } catch(e:Exception) {
            store.transaction {if(registration(key).state=="REFRESHING") {
                val c=(e as? SiwcOAuthProtocol.Failure)?.code
                when {
                    c in TERMINAL -> clear(key,"SIWC_REAUTH_REQUIRED")
                    c=="SIWC_OAUTH_TEMPORARY" -> db.execSQL("UPDATE siwc_auth_accounts SET state='ACTIVE',status=?,next_ms=? WHERE id=?",arrayOf<Any?>(c,now()+30000,key))
                    c=="SIWC_OAUTH_INVALID_CLIENT" -> db.execSQL("UPDATE siwc_auth_accounts SET state='CONFIG_HELD',status=? WHERE id=?",arrayOf(c,key))
                    else -> db.execSQL("UPDATE siwc_auth_accounts SET state='REFRESH_UNKNOWN',status='SIWC_REFRESH_OUTCOME_UNKNOWN' WHERE id=?",arrayOf(key))
                }
            };null}
        };return registration(key).state
    }
    private fun clear(key:String,reason:String) {
        db.execSQL("UPDATE siwc_auth_accounts SET record=NULL,state='SIGNED_OUT',status=? WHERE id=?",arrayOf(reason,key))
        if(activeKey()==key)db.execSQL("UPDATE siwc_auth_meta SET active='',epoch=epoch+1,pending=pending+1 WHERE id=1")
    }
    /** One bounded revocation attempt, then local logout. Unknown revocation is explicitly retained.
     * Registered identity + host survive; tokens do not. Switching/late refresh cannot resurrect it. */
    fun signOut(key:String):String {
        if(registration(key).state=="SIGNED_OUT")return registration(key).status
        val old=store.transaction {val r=registration(key);val t=tokens(key)
            db.execSQL("UPDATE siwc_auth_accounts SET state='SIGNING_OUT',process=? WHERE id=?",arrayOf(PROCESS,key))
            db.execSQL("UPDATE siwc_auth_meta SET pending=pending+1 WHERE id=1")
            if(activeKey()==key)db.execSQL("UPDATE siwc_auth_meta SET active='',epoch=epoch+1 WHERE id=1")
            r to t
        };cached=null
        val confirmed=old.second?.refresh?.takeIf{it.isNotBlank()}?.let{token->runCatching{transport.revoke(transport.metadata(),old.first.clientId,token)}.getOrDefault(false)} ?: false
        val status=if(confirmed)"SIWC_REMOTE_REVOCATION_CONFIRMED" else "SIWC_REMOTE_REVOCATION_UNKNOWN"
        store.transaction {if(registration(key).state=="SIGNING_OUT")clear(key,status);null};return status
    }
    override fun close(){cached=null;store.close()}
    companion object {
        private val PROCESS=UUID.randomUUID().toString()
        private val TERMINAL=setOf("SIWC_OAUTH_INVALID_GRANT","SIWC_OAUTH_INVALID_REFRESH_TOKEN","SIWC_OAUTH_TOKEN_EXPIRED","SIWC_OAUTH_REFRESH_TOKEN_EXPIRED","SIWC_OAUTH_REFRESH_TOKEN_INVALIDATED","SIWC_OAUTH_REFRESH_TOKEN_REUSED")
        private val SCHEMA="""CREATE TABLE siwc_auth_meta(id INTEGER PRIMARY KEY,host TEXT NOT NULL,active TEXT NOT NULL,epoch INTEGER NOT NULL,pending INTEGER NOT NULL,process TEXT NOT NULL)
-- statement-break
CREATE TABLE siwc_auth_accounts(id TEXT PRIMARY KEY,client TEXT NOT NULL,subject TEXT NOT NULL,email TEXT NOT NULL,record BLOB,state TEXT NOT NULL,process TEXT NOT NULL,status TEXT NOT NULL,next_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE siwc_auth_drafts(client TEXT PRIMARY KEY)"""
    }
}
