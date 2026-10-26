package kr.callbrief.navermail

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class ImapProtocolTest {
    private val account=AccountBinding("fixture-owner","fixture-mail@naver.com")
    private fun session(transcript:String,out:ByteArrayOutputStream=ByteArrayOutputStream())=ImapSession(account,ByteArrayInputStream(transcript.toByteArray(Charsets.ISO_8859_1)),out,Origin.SYNTHETIC_FIXTURE){ }
    private fun scope()=MailScope(account,"fixture-case","fixture-person",setOf("counterpart@example.invalid"),setOf("계약"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-08"),limit=2,relationshipConfirmedByUser=true)
    private fun login(s:ImapSession)=SecretChars(CharArray(16){('a'.code+it).toChar()}).use{s.login(it)}
    private fun failure(action:()->Unit):Exception{try{action();fail("Expected safe failure")}catch(e:Exception){return e};error("unreachable")}
    @Test fun plannerUsesExamineAndUidPeekOnly(){val h="From: counterpart@example.invalid\r\nTo: fixture-mail@naver.com\r\nSubject: contract\r\nContent-Type: text/plain; charset=US-ASCII\r\n\r\n";val transcript="* OK fixture\r\nE1 OK\r\n* OK [UIDVALIDITY 100]\r\nE2 OK [READ-ONLY]\r\n* SEARCH 1 2 3 4\r\nE3 OK\r\n* 1 FETCH (UID 4 INTERNALDATE \"3-Oct-2026 10:00:00 +0000\" BODY[HEADER.FIELDS (FROM TO SUBJECT)]<0> {${h.length}}\r\n$h)\r\nE4 OK\r\n";val out=ByteArrayOutputStream();val s=session(transcript,out);login(s);assertEquals(100L,s.examineInbox());val result=s.search(scope());assertEquals(listOf(4L,3L),result.uids);assertTrue(result.hasMore);assertEquals("contract",s.header(MailRef(account.key,"INBOX",100,4)).subject);s.close();val commands=String(out.toByteArray(),Charsets.US_ASCII);assertTrue(commands.contains("EXAMINE INBOX"));assertTrue(commands.contains("BODY.PEEK[HEADER.FIELDS"));assertFalse(Regex("\\b(SELECT|STORE|EXPUNGE|CLOSE|MOVE|COPY|APPEND|CREATE|DELETE)\\b").containsMatchIn(commands));out.reset()}
    @Test fun requireServerReadOnlyConfirmation(){val s=session("* OK fixture\r\nE1 OK\r\n* OK [UIDVALIDITY 100]\r\nE2 OK [READ-WRITE]\r\n");login(s);assertEquals(MailError.INVALID_RESPONSE,(failure{s.examineInbox()}as MailFailure).code)}
    @Test fun untaggedReadOnlyCannotOverrideTaggedReadWrite(){val s=session("* OK fixture\r\nE1 OK\r\n* OK [UIDVALIDITY 100]\r\n* OK [READ-ONLY]\r\nE2 OK [READ-WRITE]\r\n");login(s);failure{s.examineInbox()}}
    @Test fun selectedPlainBodyIsPeekAndChecksFreshHeaders() {
        val h="From: counterpart@example.invalid\r\nSubject: fixture\r\nContent-Type: text/plain; charset=US-ASCII\r\n\r\n"
        val body="todo: synthetic only"
        fun fetch(tag:String):String {
            return "* 1 FETCH (UID 1 INTERNALDATE \"3-Oct-2026 10:00:00 +0000\" BODY[HEADER.FIELDS (FROM)]<0> {${h.length}}\r\n$h)\r\n$tag OK\r\n"
        }
        val input="* OK fixture\r\nE1 OK\r\n* OK [UIDVALIDITY 100]\r\nE2 OK [READ-ONLY]\r\n"+fetch("E3")+fetch("E4")+
            "* 1 FETCH (UID 1 BODY[TEXT]<0> {${body.length}}\r\n$body)\r\nE5 OK\r\n"
        val out=ByteArrayOutputStream();val s=session(input,out);login(s);s.examineInbox()
        val header=s.header(MailRef(account.key,"INBOX",100,1))
        assertEquals(body,s.selectedPlainBody(header).text)
        assertTrue(String(out.toByteArray()).contains("BODY.PEEK[TEXT]<0.32768>"))
        assertFalse(String(out.toByteArray()).contains("\\Seen"));out.reset()
    }
    @Test fun uidValidityMissingNeverFallsBackToOldUid(){val s=session("* OK fixture\r\nE1 OK\r\nE2 OK [READ-ONLY]\r\n");login(s);failure{s.examineInbox()}}
    @Test fun serverAuthTextAndSecretsAreNotInError(){val s=session("* OK fixture\r\nE1 NO [AUTHENTICATIONFAILED] fixture-private-server-text\r\n");val e=failure{login(s)};assertEquals("IMAP_PERMISSION_OR_AUTH",e.message);assertFalse(e.toString().contains("fixture-private"))}
    @Test fun hugeLiteralIsRejectedBeforeAllocatingBody(){val s=session("* OK fixture\r\nE1 OK\r\n* OK [UIDVALIDITY 100]\r\nE2 OK [READ-ONLY]\r\n* 1 FETCH (UID 1 BODY[HEADER.FIELDS (FROM)] {999999999}\r\n");login(s);s.examineInbox();failure{s.header(MailRef(account.key,"INBOX",100,1))}}
    @Test fun externalAccountCannotFetchARef(){val s=session("* OK fixture\r\nE1 OK\r\n* OK [UIDVALIDITY 100]\r\nE2 OK [READ-ONLY]\r\n");login(s);s.examineInbox();failure{s.header(MailRef(AccountBinding("fixture-other","fixture-mail@naver.com").key,"INBOX",100,1))}}
    @Test fun displayNameEmailCannotSpoofAnExactCounterpart(){assertEquals(setOf("other@example.invalid"),Headers.addresses("\"counterpart@example.invalid\" <other@example.invalid>"));assertTrue(Headers.addresses("invalid (counterpart@example.invalid)").isEmpty())}
    @Test fun mimeDecoderPreservesKoreanEvidence(){val raw="필요서류: 신청서".toByteArray(Charsets.UTF_8);val encoded=java.util.Base64.getMimeEncoder().encode(raw);assertEquals("필요서류: 신청서",Headers.decodeBody(encoded,"base64","UTF-8"));val subject="=?UTF-8?B?"+java.util.Base64.getEncoder().encodeToString("계약".toByteArray())+"?=";assertEquals("계약",Headers.decodeSubject(subject))}
    @Test fun duplicateCriticalHeadersFailClosed(){failure{Headers.parse("From: a@example.invalid\r\nFrom: b@example.invalid\r\n".toByteArray())}}
    @Test fun invalidTransferEncodingIsUnprocessed(){assertEquals(MailError.UNSUPPORTED_MIME,(failure{Headers.decodeBody(byteArrayOf(1),"unknown","UTF-8")}as MailFailure).code)}
    @Test fun unsupportedHeaderCharsetsRemainUncertain(){assertEquals("[제목 디코딩 미처리]",Headers.decodeSubject("=?X-UNSUPPORTED?B?YQ==?="))}
    @Test fun deniedProductionAdapterCannotOpenNetwork(){val e=failure{NaverImapMailbox(AccountActionPolicy.denied()).open(account,SecretChars(CharArray(16){'a'}))};assertEquals(MailError.APPROVAL_REQUIRED,(e as MailFailure).code)}
}
