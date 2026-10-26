package kr.callbrief

import kr.callagent.drive.StrictJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

internal class SiwcAuthFixture {
    val key=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
    val now=1793000000000L
    private fun unsigned(b:ByteArray)=if(b[0]==0.toByte())b.copyOfRange(1,b.size) else b
    val jwks:ByteArray get() {val k=key.public as RSAPublicKey;return StrictJson.bytes(mapOf("keys" to listOf(mapOf("kid" to "fixture-key","kty" to "RSA","alg" to "RS256","use" to "sig",
        "n" to SiwcOAuthProtocol.encode(unsigned(k.modulus.toByteArray())),"e" to SiwcOAuthProtocol.encode(unsigned(k.publicExponent.toByteArray()))))))}
    val metadata=SiwcOAuthProtocol.Metadata("https://auth.openai.com/.well-known/jwks.json","https://auth.openai.com/api/accounts/oauth/revoke")
    fun pending(client:String?="fixture-client",subject:String?=null)=SiwcOAuthProtocol.Pending(SiwcOAuthConfig("PUBLIC_SYNTHETIC_REGISTRATION_FIXTURE",client,client==null),"urn:uuid:fixture-host","http://127.0.0.1:1455/auth/callback",true,subject,1,now)
    fun callback(p:SiwcOAuthProtocol.Pending,client:String?=p.config.issuedClientId)=p.redirect+"?code=PUBLIC_CODE&state="+p.state+(client?.let{"&client_id=$it"} ?: "")
    fun jwt(p:SiwcOAuthProtocol.Pending,client:String=p.config.issuedClientId ?: "fixture-new-client",subject:String="fixture-subject",time:Long=now,
        change:(MutableMap<String,Any?>)->Unit={},header:Map<String,Any?> = mapOf("alg" to "RS256","kid" to "fixture-key")):String {
        val claims=mutableMapOf<String,Any?>("iss" to SiwcOAuthProtocol.ISSUER,"sub" to subject,"aud" to client,"nonce" to p.nonce,"iat" to time/1000,"exp" to (time/1000+3600),"email" to "public@example.invalid");change(claims)
        val h=SiwcOAuthProtocol.encode(StrictJson.bytes(header));val c=SiwcOAuthProtocol.encode(StrictJson.bytes(claims));val unsigned="$h.$c"
        val sig=Signature.getInstance("SHA256withRSA").run{initSign(key.private);update(unsigned.toByteArray());sign()};return unsigned+"."+SiwcOAuthProtocol.encode(sig)
    }
    fun tokens(p:SiwcOAuthProtocol.Pending,time:Long=now,client:String=p.config.issuedClientId ?: "fixture-new-client",subject:String="fixture-subject",
        scopes:Set<String> = p.requestedScopes,change:(MutableMap<String,Any?>)->Unit={}):ByteArray {
        val x=mutableMapOf<String,Any?>("access_token" to "PUBLIC_ACCESS_NOT_A_REAL_CREDENTIAL_12345678","refresh_token" to "PUBLIC_REFRESH_NOT_A_REAL_CREDENTIAL_12345678",
            "id_token" to jwt(p,client,subject,time),"token_type" to "Bearer","expires_in" to 3600L,"scope" to scopes.sorted().joinToString(" "))
        if("offline_access" !in scopes)x.remove("refresh_token");change(x);return StrictJson.bytes(x)
    }
}
class SiwcOAuthProtocolTest {
    private val f=SiwcAuthFixture()
    private fun failCode(code:String,block:()->Unit){val e=runCatching(block).exceptionOrNull();assertNotNull(e);assertTrue(e.toString(),e!!.message.orEmpty().contains(code))}
    @Test fun noUnapprovedDynamicOrPlaceholderIssuedClient(){failCode("Failed requirement"){SiwcOAuthConfig("pending",null)};failCode("SIWC_ISSUED_CLIENT_REQUIRED"){SiwcOAuthConfig("pending","dynamic_agent_client")}}
    @Test fun pkceUsesFreshEntropyAndExactLoopbackAndResource(){val p=f.pending();val u=p.authorizeUrl().toHttpUrl()
        assertEquals(SiwcOAuthProtocol.RESOURCE,u.queryParameter("resource"));assertEquals(p.redirect,u.queryParameter("redirect_uri"));assertEquals("S256",u.queryParameter("code_challenge_method"))
        assertFalse(p.authorizeUrl().contains(p.verifier));assertNull(u.queryParameter("agent_name_hint"));assertNotEquals(p.state,f.pending().state)
        val c=p.callback(f.callback(p),f.now);val exchange=p.exchange(c);assertEquals(p.verifier,exchange["code_verifier"]);assertEquals(p.redirect,exchange["redirect_uri"])}
    @Test fun codeCanOnlyBeExchangedOnceByItsOwnAttempt(){val p=f.pending();val code=p.callback(f.callback(p),f.now)
        failCode("SIWC_CODE_ATTEMPT_BINDING"){f.pending().exchange(code)};p.exchange(code);failCode("SIWC_CODE_ALREADY_EXCHANGED"){p.exchange(code)}}
    @Test fun dynamicRequiresCallbackIssuedIdAndOnlyInitialRequestHasAppName(){val p=f.pending(null);assertEquals("이음",p.authorizeUrl().toHttpUrl().queryParameter("agent_name_hint"))
        failCode("SIWC_ISSUED_CLIENT_REQUIRED"){p.callback(f.callback(p),f.now)};assertEquals("issued-fixture",p.callback(f.callback(p,"issued-fixture"),f.now).clientId)}
    @Test fun wrongStateDuplicateQueryAndChangedClientAreRejected(){val p=f.pending()
        failCode("SIWC_CALLBACK_STATE"){p.callback(f.callback(p).replace(p.state,"wrong"),f.now)}
        failCode("SIWC_CALLBACK_DUPLICATE"){p.callback(f.callback(p)+"&code=second",f.now)}
        failCode("SIWC_CALLBACK_CLIENT_CHANGED"){p.callback(f.callback(p,"other"),f.now)}}
    @Test fun wrongTargetExpirationDenialAndReplayDoNotExchange(){val p=f.pending()
        failCode("SIWC_CALLBACK_TARGET"){p.callback(f.callback(p).replace("127.0.0.1","localhost"),f.now)}
        failCode("SIWC_LOGIN_EXPIRED"){p.callback(f.callback(p),f.now+300001)}
        p.callback(f.callback(p),f.now);failCode("SIWC_CALLBACK_REPLAY"){p.callback(f.callback(p),f.now)}
        val denied=f.pending();failCode("SIWC_USER_DENIED"){denied.callback(denied.redirect+"?state="+denied.state+"&error=access_denied",f.now)}}
    @Test fun signatureAudienceIssuerNonceAndIdentityAreRequired(){val p=f.pending();fun validate(jwt:String)=SiwcOAuthProtocol.identity(jwt,f.jwks,"fixture-client",p.nonce,f.now,"fixture-subject")
        assertEquals("fixture-subject",validate(f.jwt(p)).subject)
        failCode("SIWC_JWT_ISSUER"){validate(f.jwt(p,change={it["iss"]="https://example.invalid"}))}
        failCode("SIWC_JWT_AUDIENCE"){validate(f.jwt(p,change={it["aud"]="other"}))}
        failCode("SIWC_JWT_NONCE"){validate(f.jwt(p,change={it["nonce"]="other"}))}
        failCode("SIWC_ACCOUNT_CHANGED"){validate(f.jwt(p,subject="other"))}}
    @Test fun untrustedAlgorithmKeyUrlsAndSignatureFail(){val p=f.pending();fun validate(jwt:String)=SiwcOAuthProtocol.identity(jwt,f.jwks,"fixture-client",p.nonce,f.now)
        failCode("SIWC_JWT_ALGORITHM"){validate(f.jwt(p,header=mapOf("alg" to "none","kid" to "fixture-key")))}
        failCode("SIWC_JWT_ALGORITHM"){validate(f.jwt(p,header=mapOf("alg" to "RS256","kid" to "fixture-key","jku" to "https://example.invalid/keys")))}
        failCode("SIWC_JWT_SIGNATURE"){SiwcOAuthProtocol.identity(f.jwt(p),SiwcAuthFixture().jwks,"fixture-client",p.nonce,f.now)}}
    @Test fun expiredFutureAndAmbiguousAudiencesAreRejected(){val p=f.pending();fun validate(change:(MutableMap<String,Any?>)->Unit)=SiwcOAuthProtocol.identity(f.jwt(p,change=change),f.jwks,"fixture-client",p.nonce,f.now)
        failCode("SIWC_JWT_TIME"){validate{it["exp"]=f.now/1000}}
        failCode("SIWC_JWT_TIME"){validate{it["nbf"]=f.now/1000+300}}
        failCode("SIWC_JWT_AUDIENCE"){validate{it["aud"]=listOf("fixture-client","another")}}
        assertEquals("fixture-subject",validate{it["aud"]=listOf("fixture-client","another");it["azp"]="fixture-client"}.subject)}
    @Test fun tokenHashBindingIsCheckedWhenPresent(){val p=f.pending();failCode("SIWC_JWT_TOKEN_BINDING"){SiwcOAuthProtocol.identity(f.jwt(p,change={it["at_hash"]="wrong"}),f.jwks,"fixture-client",p.nonce,f.now,accessToken="public-token")}}
    @Test fun metadataCannotReplaceIssuerOrSendKeysToOtherOrigin(){failCode("SIWC_OAUTH_ORIGIN"){SiwcOAuthProtocol.Metadata("https://example.invalid/keys",f.metadata.revocationEndpoint)}
        failCode("SIWC_DISCOVERY_MISMATCH"){SiwcOAuthProtocol.metadata(StrictJson.bytes(mapOf("issuer" to "https://example.invalid")))}}
}
