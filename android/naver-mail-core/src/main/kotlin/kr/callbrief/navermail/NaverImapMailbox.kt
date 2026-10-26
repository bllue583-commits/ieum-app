package kr.callbrief.navermail

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Restricted protocol adapter. Nothing constructs or opens it in the installed code11 patch. */
class NaverImapMailbox(private val policy: AccountActionPolicy) : ReadOnlyMailbox {
    override val origin = Origin.IMAP_REAL
    override fun open(account: AccountBinding, secret: SecretChars): ReadOnlyMailbox.Session {
        policy.requireApproved() // Must run before DNS, socket, authentication or reads.
        var socket: Socket?=null
        try {
            val tcp=Socket();socket=tcp;tcp.connect(InetSocketAddress(HOST,PORT),15_000);tcp.soTimeout=15_000
            val tls=SSLSocketFactory.getDefault().let { it as SSLSocketFactory }.createSocket(tcp,HOST,PORT,true) as SSLSocket
            socket=tls;tls.soTimeout=15_000
            tls.enabledProtocols=tls.supportedProtocols.filter { it in setOf("TLSv1.2","TLSv1.3") }.toTypedArray()
            tls.sslParameters=tls.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS" }
            tls.startHandshake() // Platform trust chain AND hostname checks; no custom trust manager.
            val session=ImapSession(account,tls.getInputStream(),tls.getOutputStream()) { tls.close() }
            session.login(secret);return session
        } catch (_: SSLException) { runCatching { socket?.close() };throw MailFailure(MailError.TLS_REJECTED) }
        catch(e: MailFailure) { runCatching { socket?.close() };throw e }
        catch (_: Exception) { runCatching { socket?.close() };throw MailFailure(MailError.NETWORK) }
    }
    companion object { const val HOST="imap.naver.com";const val PORT=993 }
}

/** Internal transcript seam permits offline adversarial protocol tests without a network factory. */
internal class ImapSession(private val account: AccountBinding,private val input: InputStream,
    private val output: OutputStream,private val origin:Origin=Origin.IMAP_REAL,private val abort:()->Unit) : ReadOnlyMailbox.Session {
    private var serial=0;@Volatile private var closed=false;private var uidValidity=0L
    private data class Frame(val line:String,val literal:ByteArray?)
    private fun fail(code:MailError=MailError.INVALID_RESPONSE):Nothing { close();throw MailFailure(code) }
    private fun line():String {
        val b=ByteArrayOutputStream()
        while(true) { val c=input.read();if(c<0)fail(MailError.NETWORK);if(c==10)break
            if(b.size()>=32768)fail();b.write(c) }
        val bytes=b.toByteArray();if(bytes.isEmpty() || bytes.last()!=13.toByte())fail()
        return String(bytes,0,bytes.size-1,Charsets.ISO_8859_1)
    }
    private fun response(tag:String,error:MailError):List<Frame> {
        val frames=mutableListOf<Frame>();var budget=0
        while(frames.size<400) {
            if(closed)throw MailFailure(MailError.CANCELLED)
            val l=line();budget+=l.length;if(budget>262144)fail()
            if(l.startsWith("$tag ")) {
                if(l.split(' ',limit=3).getOrNull(1)?.uppercase(Locale.ROOT)!="OK") {
                    val mapped=when {
                        l.contains("[LIMIT]",true)->MailError.RATE_LIMITED
                        l.contains("[UNAVAILABLE]",true)->MailError.NETWORK
                        else->error
                    };fail(mapped)
                }
                return frames + Frame(l,null)
            }
            if(l.startsWith("* BYE",true))fail(MailError.NETWORK)
            val size=Regex("\\{(\\d+)\\}$").find(l)?.groupValues?.get(1)?.toIntOrNull()
            var bytes:ByteArray?=null
            if(size!=null) {
                if(size !in 0..32768 || budget+size>262144)fail()
                bytes=ByteArray(size);var p=0
                while(p<size) { val n=input.read(bytes,p,size-p);if(n<=0)fail(MailError.NETWORK);p+=n }
                budget+=size
            }
            // IMAP server response text is never included in exception messages, UI or logs.
            frames+=Frame(l,bytes)
        };fail()
    }
    private fun execute(command:String,error:MailError=MailError.INVALID_RESPONSE):List<Frame> {
        if(closed)throw MailFailure(MailError.CANCELLED)
        require(!command.contains('\r') && !command.contains('\n'))
        val tag="E${++serial}";output.write("$tag $command\r\n".toByteArray(Charsets.US_ASCII));output.flush()
        return try { response(tag,error) } catch(e:MailFailure) { throw e } catch(_:Exception) { fail(MailError.NETWORK) }
    }
    fun login(secret:SecretChars) {
        val greeting=line();if(!greeting.startsWith("* OK",true))fail(MailError.IMAP_PERMISSION_OR_AUTH)
        val tag="E${++serial}"
        // App password is ASCII and normally contains letters. No fallback to account password/OAuth.
        secret.useChars { chars ->
            if(chars.size !in 8..128 || chars.any { it.code !in 33..126 })fail(MailError.AUTH_REQUIRED)
            output.write("$tag LOGIN \"${account.address}\" \"".toByteArray(Charsets.US_ASCII))
            val bytes=ByteArray(chars.size*2);var p=0
            try {
                for(c in chars) { if(c=='"' || c=='\\')bytes[p++]='\\'.code.toByte();bytes[p++]=c.code.toByte() }
                output.write(bytes,0,p);output.write("\"\r\n".toByteArray(Charsets.US_ASCII));output.flush()
            } finally { bytes.fill(0) }
        }
        response(tag,MailError.IMAP_PERMISSION_OR_AUTH)
    }
    override fun examineInbox():Long {
        val r=execute("EXAMINE INBOX",MailError.IMAP_PERMISSION_OR_AUTH)
        val validity=r.mapNotNull { Regex("\\[UIDVALIDITY (\\d+)\\]",RegexOption.IGNORE_CASE).find(it.line)?.groupValues?.get(1)?.toLongOrNull() }.singleOrNull()
        if(validity==null || validity<=0 || !r.last().line.contains("[READ-ONLY]",true))fail()
        uidValidity=validity;return validity
    }
    override fun search(scope:MailScope):SearchResult {
        require(scope.account==account && uidValidity>0)
        val fmt=DateTimeFormatter.ofPattern("dd-MMM-yyyy",Locale.ENGLISH)
        val selectors=scope.correspondentAddresses.sorted().map { "OR FROM ${quoted(it)} TO ${quoted(it)}" }
        val filter=selectors.reduceRight { a,b -> "OR ($a) ($b)" }
        val r=execute("UID SEARCH SINCE ${scope.since.format(fmt)} BEFORE ${scope.before.format(fmt)} ($filter)")
        val lines=r.filter { it.line.startsWith("* SEARCH",true) };if(lines.size!=1)fail()
        val tokens=lines.single().line.removePrefix("* SEARCH").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val uids=tokens.map { it.toLongOrNull()?.takeIf { v->v>0 } ?: fail() }.distinct().sortedDescending()
        return SearchResult(uids.take(scope.limit),uids.size>scope.limit)
    }
    override fun header(ref:MailRef):HeaderEnvelope {
        checkRef(ref)
        val r=execute("UID FETCH ${ref.uid} (UID INTERNALDATE BODY.PEEK[HEADER.FIELDS (MESSAGE-ID FROM TO SUBJECT DATE CONTENT-TYPE CONTENT-DISPOSITION CONTENT-TRANSFER-ENCODING)]<0.16384>)")
        val f=r.singleOrNull { it.literal!=null && it.line.contains("BODY[HEADER.FIELDS",true) && uidMatches(it.line,ref.uid) } ?: fail()
        val raw=f.literal!!;if(raw.size>16384)fail()
        val dateText=Regex("INTERNALDATE \"([^\"]+)\"",RegexOption.IGNORE_CASE).find(f.line)?.groupValues?.get(1) ?: fail()
        val time=try { ZonedDateTime.parse(dateText.trim(),DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z",Locale.ENGLISH)) }catch(_:Exception){fail()}
        val fields=Headers.parse(raw)
        val contentType=fields["content-type"] ?: "text/plain; charset=US-ASCII"
        val mime=contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val charset=Regex("charset\\s*=\\s*\"?([^;\" ]+)",RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1) ?: "US-ASCII"
        val disposition=fields["content-disposition"].orEmpty()+if(Regex("(?:^|;)\\s*name\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(contentType))"; attachment-name-unprocessed" else ""
        return HeaderEnvelope(ref,time.toInstant(),fields["message-id"]?.takeIf { it.length<=200 },
            Headers.addresses(fields["from"].orEmpty()),Headers.addresses(fields["to"].orEmpty()),
            Headers.decodeSubject(fields["subject"].orEmpty()),digestBytes(raw),mime,
            disposition,fields["content-transfer-encoding"]?:"7bit",charset,raw.size==16384,origin=origin,mailboxDate=time.toLocalDate())
    }
    override fun selectedPlainBody(header:HeaderEnvelope):PlainBody {
        checkRef(header.ref)
        if(!MailIntake.plainEligible(header))throw MailFailure(MailError.UNSUPPORTED_MIME)
        // Re-fetch headers before body access: UID is stable within UIDVALIDITY; MIME assumptions must still match.
        val latest=this.header(header.ref)
        if(latest.rawHeaderSha!=header.rawHeaderSha || !MailIntake.plainEligible(latest))fail()
        val r=execute("UID FETCH ${header.ref.uid} (UID BODY.PEEK[TEXT]<0.32768>)")
        val f=r.singleOrNull { it.literal!=null && it.line.contains("BODY[TEXT]",true) && uidMatches(it.line,header.ref.uid) } ?: fail()
        val raw=f.literal!!;val text=Headers.decodeBody(raw,header.transferEncoding,header.charset)
        return PlainBody(header.ref,text,raw.size==32768,origin)
    }
    private fun checkRef(ref:MailRef) { require(ref.accountKey==account.key && ref.uidValidity==uidValidity && ref.mailbox=="INBOX") }
    // Immediate socket close also interrupts a pending read. Never CLOSE/EXPUNGE/STORE or a logout flag mutation.
    override fun close() { closed=true;runCatching { abort() } }
    private fun quoted(s:String):String { require(s.all { it.code in 33..126 });return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"" }
    private fun uidMatches(line:String,uid:Long)=Regex("\\bUID (\\d+)\\b",RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull()==uid
}

internal object Headers {
    fun parse(raw:ByteArray):Map<String,String> {
        val s=String(raw,Charsets.ISO_8859_1).replace("\r\n "," ").replace("\r\n\t"," ")
        val out=mutableMapOf<String,String>()
        for(line in s.split("\r\n")) {
            if(line.isEmpty())continue
            val pos=line.indexOf(':');if(pos<=0)throw MailFailure(MailError.INVALID_RESPONSE)
            val key=line.substring(0,pos).lowercase(Locale.ROOT)
            if(out.containsKey(key))throw MailFailure(MailError.INVALID_RESPONSE)
            out[key]=line.substring(pos+1).trim()
        };return out
    }
    /** Conservative mailbox grammar; unsupported groups/comments are uncertain and excluded. */
    fun addresses(value:String):Set<String> {
        val tokens=mutableListOf<String>();val b=StringBuilder();var quote=false;var escape=false
        for(c in value) { if(escape){b.append(c);escape=false;continue};if(c=='\\' && quote){b.append(c);escape=true;continue}
            if(c=='"')quote=!quote
            if(c==',' && !quote){tokens+=b.toString();b.clear()}else b.append(c) }
        if(quote)return emptySet();tokens+=b.toString()
        return tokens.mapNotNull { token ->
            val t=token.trim();val angle=Regex("^(?:[^<>]* )?<([^<>]+)>$").matchEntire(t)
            val a=angle?.groupValues?.get(1)?:t
            a.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9.!#%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+")) }
        }.toSet()
    }
    private fun charset(name:String):Charset {
        if(name.uppercase(Locale.ROOT) !in setOf("UTF-8","US-ASCII","ISO-8859-1","EUC-KR","KS_C_5601-1987"))throw MailFailure(MailError.UNSUPPORTED_MIME)
        return try { Charset.forName(name) }catch(_:Exception){throw MailFailure(MailError.UNSUPPORTED_MIME)}
    }
    fun decodeSubject(s:String):String {
        return try { Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").replace(s) { m ->
            val bytes=if(m.groupValues[2].equals("b",true))Base64.getDecoder().decode(m.groupValues[3]) else qp(m.groupValues[3].replace('_',' ').toByteArray(Charsets.ISO_8859_1))
            charset(m.groupValues[1]).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } }catch(_:Exception){"[제목 디코딩 미처리]"}
    }
    fun decodeBody(bytes:ByteArray,encoding:String,name:String):String = try {
        val decoded=when(encoding.lowercase(Locale.ROOT)) {
            "base64"->Base64.getMimeDecoder().decode(bytes)
            "quoted-printable"->qp(bytes)
            "7bit","8bit","binary"->bytes
            else->throw MailFailure(MailError.UNSUPPORTED_MIME)
        }
        charset(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString().replace("\r\n","\n")
    }catch(e:MailFailure){throw e}catch(_:Exception){throw MailFailure(MailError.UNSUPPORTED_MIME)}
    private fun qp(b:ByteArray):ByteArray {
        val out=ByteArrayOutputStream();var i=0
        while(i<b.size) { val c=b[i].toInt()and 255
            if(c!=61){out.write(c);i++}else if(i+2<b.size && b[i+1]==13.toByte() && b[i+2]==10.toByte()){i+=3}
            else { if(i+2>=b.size)throw MailFailure(MailError.UNSUPPORTED_MIME)
                val a=Character.digit(b[i+1].toInt().toChar(),16);val d=Character.digit(b[i+2].toInt().toChar(),16)
                if(a<0||d<0)throw MailFailure(MailError.UNSUPPORTED_MIME);out.write(a*16+d);i+=3 }
        };return out.toByteArray()
    }
}
