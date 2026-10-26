package kr.callbrief

import kr.callagent.drive.StrictJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Native app configuration only. No default, remote flag, inbound Intent, or model may enable it.
 * admissionReference records the integration's approved route; it does not manufacture admission.
 * A null issued ID means first-time dynamic registration, requiring its separate explicit approval. */
internal class SiwcOAuthConfig(val admissionReference:String,val issuedClientId:String?,val dynamicRegistrationApproved:Boolean=false) {
    init {require(admissionReference.isNotBlank() && admissionReference.length<=1024)
        if(issuedClientId==null)require(dynamicRegistrationApproved) else SiwcOAuthProtocol.clientId(issuedClientId)}
    override fun toString()="SiwcOAuthConfig[withheld]"
}
internal object SiwcOAuthProtocol {
    const val ISSUER="https://auth.openai.com"
    const val DISCOVERY="$ISSUER/.well-known/openid-configuration"
    const val AUTHORIZE="$ISSUER/api/accounts/authorize"
    const val TOKEN="$ISSUER/api/accounts/oauth/token"
    const val RESOURCE="https://api.openai.com/v1"
    val IDENTITY=setOf("openid","profile","email")
    val PLAN=setOf("offline_access","resource.invoke","chatgpt.tokens.use.direct")
    class Failure(val code:String):IllegalStateException(code)
    class Metadata(val jwksUri:String,val revocationEndpoint:String) {
        init {official(jwksUri);official(revocationEndpoint)}
        override fun toString()="SiwcOAuthMetadata"
    }
    fun official(url:String) {val u=url.toHttpUrl();require(u.scheme=="https" && u.host=="auth.openai.com" && u.port==443 && u.username.isEmpty() && u.password.isEmpty() && u.query==null && u.fragment==null) { "SIWC_OAUTH_ORIGIN" }}
    fun metadata(bytes:ByteArray):Metadata {
        val x=AstraAudioContract.obj(StrictJson.readOnlyArtifact(bytes))
        check(x["issuer"]==ISSUER && x["authorization_endpoint"]==AUTHORIZE && x["token_endpoint"]==TOKEN) { "SIWC_DISCOVERY_MISMATCH" }
        for((key,value) in listOf("id_token_signing_alg_values_supported" to "RS256","code_challenge_methods_supported" to "S256","token_endpoint_auth_methods_supported" to "none"))
            check(value in AstraAudioContract.list(x,key,30)) { "SIWC_DISCOVERY_UNSUPPORTED" }
        return Metadata(AstraAudioContract.text(x,"jwks_uri",2048),AstraAudioContract.text(x,"revocation_endpoint",2048))
    }
    fun clientId(value:String) {require(value!="dynamic_agent_client" && value.matches(Regex("[A-Za-z0-9_.:-]{1,256}"))) { "SIWC_ISSUED_CLIENT_REQUIRED" }}
    fun encode(bytes:ByteArray):String=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun random()=encode(ByteArray(32).also{SecureRandom().nextBytes(it)})
    private fun same(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8),b.toByteArray(Charsets.UTF_8))
    class Pending(val config:SiwcOAuthConfig,val hostId:String,val redirect:String,val requestPlan:Boolean,
        val selectedSubject:String?,val epoch:Long,val createdMs:Long) {
        val state=random();val nonce=random();val verifier=random();private val consumed=AtomicBoolean(false);private val exchanged=AtomicBoolean(false)
        val requestedScopes=IDENTITY+if(requestPlan)PLAN else emptySet()
        init {val u=URI(redirect);require(u.scheme=="http" && u.host=="127.0.0.1" && u.port in 1..65535 && u.rawPath=="/auth/callback" && u.rawQuery==null && u.rawFragment==null && u.rawUserInfo==null)
            require(hostId.startsWith("urn:uuid:") && hostId.length<=128);if(selectedSubject!=null)require(config.issuedClientId!=null)}
        fun authorizeUrl():String {
            val b=AUTHORIZE.toHttpUrl().newBuilder()
            val fields=linkedMapOf("client_id" to (config.issuedClientId ?: "dynamic_agent_client"),"ext_agent_host_id" to hostId,"response_type" to "code",
                "redirect_uri" to redirect,"scope" to requestedScopes.sorted().joinToString(" "),"resource" to RESOURCE,"state" to state,"nonce" to nonce,
                "code_challenge_method" to "S256","code_challenge" to encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))
            if(config.issuedClientId==null)fields["agent_name_hint"]="이음"
            fields.forEach{(k,v)->b.addQueryParameter(k,v)};return b.build().toString()
        }
        fun callback(url:String,now:Long):Code {
            require(now>=createdMs && now-createdMs<300000) { "SIWC_LOGIN_EXPIRED" }
            val u=URI(url);val expected=URI(redirect)
            check(u.scheme==expected.scheme && u.rawAuthority==expected.rawAuthority && u.rawPath==expected.rawPath && u.rawFragment==null) { "SIWC_CALLBACK_TARGET" }
            val query=u.rawQuery ?: error("SIWC_CALLBACK_QUERY");check(query.length<=8192) { "SIWC_CALLBACK_SIZE" }
            val values=linkedMapOf<String,String>()
            for(pair in query.split('&')) {val parts=pair.split('=',limit=2);check(parts.size==2) { "SIWC_CALLBACK_QUERY" }
                val k=URLDecoder.decode(parts[0],"UTF-8");val v=URLDecoder.decode(parts[1],"UTF-8")
                check(k !in values && v.none{it.code<32 || it.code==127}) { "SIWC_CALLBACK_DUPLICATE_OR_CONTROL" };values[k]=v}
            check(same(values["state"].orEmpty(),state)) { "SIWC_CALLBACK_STATE" }
            check(values["iss"]==null || values["iss"]==ISSUER) { "SIWC_CALLBACK_ISSUER" }
            if(values["error"]!=null) {check(values["code"]==null) { "SIWC_CALLBACK_AMBIGUOUS" };check(consumed.compareAndSet(false,true)) { "SIWC_CALLBACK_REPLAY" };throw Failure(if(values["error"]=="access_denied")"SIWC_USER_DENIED" else "SIWC_AUTHORIZATION_FAILED")}
            val id=values["client_id"] ?: config.issuedClientId ?: error("SIWC_ISSUED_CLIENT_REQUIRED");clientId(id)
            check(config.issuedClientId==null || config.issuedClientId==id) { "SIWC_CALLBACK_CLIENT_CHANGED" }
            val code=values["code"] ?: error("SIWC_CODE_REQUIRED");check(code.length in 1..4096) { "SIWC_CODE_REQUIRED" }
            check(consumed.compareAndSet(false,true)) { "SIWC_CALLBACK_REPLAY" };return Code(id,code,this)
        }
        fun exchange(code:Code):Map<String,String> {check(code.pending===this) { "SIWC_CODE_ATTEMPT_BINDING" };check(exchanged.compareAndSet(false,true)) { "SIWC_CODE_ALREADY_EXCHANGED" };return mapOf("grant_type" to "authorization_code","client_id" to code.clientId,"code" to code.value,"code_verifier" to verifier,"redirect_uri" to redirect,"resource" to RESOURCE)}
        override fun toString()="SiwcPendingLogin[withheld]"
    }
    class Code(val clientId:String,val value:String,internal val pending:Pending) {override fun toString()="SiwcAuthorizationCode[withheld]"}
    class Identity(val subject:String,val email:String?,val nonce:String?) {override fun toString()="SiwcIdentity[withheld]"}
    private fun decode(part:String):ByteArray {require(part.length in 1..65536 && part.matches(Regex("[A-Za-z0-9_-]+"))) { "SIWC_JWT_FORMAT" };return Base64.getUrlDecoder().decode(part)}
    /** Only RS256 from issuer-owned HTTPS JWKS. JWT-controlled key URLs and algorithms are rejected. */
    fun identity(jwt:String,jwks:ByteArray,clientId:String,nonce:String?,nowMs:Long,selectedSubject:String?=null,
        accessToken:String?=null,code:String?=null,requireNonce:Boolean=true):Identity {
        SiwcOAuthProtocol.clientId(clientId);val parts=jwt.split('.');check(parts.size==3 && jwt.length<=65536) { "SIWC_JWT_FORMAT" }
        val h=AstraAudioContract.parse(decode(parts[0]));check(h["alg"]=="RS256" && h["crit"]==null && h["jku"]==null && h["x5u"]==null && h["jwk"]==null) { "SIWC_JWT_ALGORITHM" }
        val kid=AstraAudioContract.text(h,"kid");val candidates=AstraAudioContract.list(AstraAudioContract.parse(jwks),"keys",30).map(AstraAudioContract::obj).filter{it["kid"]==kid}
        check(candidates.size==1) { "SIWC_JWT_KEY" };val k=candidates.single()
        check(k["kty"]=="RSA" && (k["use"]==null || k["use"]=="sig") && (k["alg"]==null || k["alg"]=="RS256") && (k["key_ops"]==null || "verify" in AstraAudioContract.list(k,"key_ops",10))) { "SIWC_JWT_KEY" }
        val n=BigInteger(1,decode(AstraAudioContract.text(k,"n",2048)));val e=BigInteger(1,decode(AstraAudioContract.text(k,"e",20)))
        check(n.bitLength() in 2048..8192 && e.bitLength()<=32 && e>=BigInteger.valueOf(3) && e.testBit(0)) { "SIWC_JWT_KEY" }
        val key=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n,e))
        check(Signature.getInstance("SHA256withRSA").run {initVerify(key);update((parts[0]+"."+parts[1]).toByteArray(Charsets.US_ASCII));verify(decode(parts[2]))}) { "SIWC_JWT_SIGNATURE" }
        val c=AstraAudioContract.parse(decode(parts[1]));check(c["iss"]==ISSUER) { "SIWC_JWT_ISSUER" }
        val aud=when(val a=c["aud"]) {is String->listOf(a);is List<*>->a.map{it as? String ?: error("SIWC_JWT_AUDIENCE")};else->error("SIWC_JWT_AUDIENCE")}
        check(clientId in aud && aud.distinct().size==aud.size && (aud.size==1 || c["azp"]==clientId) && (c["azp"]==null || c["azp"]==clientId)) { "SIWC_JWT_AUDIENCE" }
        val now=nowMs/1000;val expiry=AstraAudioContract.number(c,"exp");val issued=AstraAudioContract.number(c,"iat")
        check(expiry>now && issued<=now+60 && expiry>issued && (c["nbf"]==null || AstraAudioContract.number(c,"nbf")<=now+60)) { "SIWC_JWT_TIME" }
        check((!requireNonce && c["nonce"]==null) || nonce!=null && c["nonce"] is String && same(c["nonce"] as String,nonce)) { "SIWC_JWT_NONCE" }
        val sub=AstraAudioContract.text(c,"sub");check(selectedSubject==null || same(selectedSubject,sub)) { "SIWC_ACCOUNT_CHANGED" }
        for((claim,value) in listOf("at_hash" to accessToken,"c_hash" to code))if(c[claim]!=null) {
            check(value!=null && c[claim] is String && same(c[claim] as String,encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)).copyOf(16)))) { "SIWC_JWT_TOKEN_BINDING" }
        }
        return Identity(sub,(c["email"] as? String)?.takeIf{it.length<=320},c["nonce"] as? String)
    }
}
