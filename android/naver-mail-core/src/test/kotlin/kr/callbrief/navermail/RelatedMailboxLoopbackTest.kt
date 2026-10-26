package kr.callbrief.navermail

import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.net.*
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.*

/** Actual protocol bytes on 127.0.0.1, using generated accounts/content only; no TLS weakening. */
class RelatedMailboxLoopbackTest {
    private val account=AccountBinding("PUBLIC_LOOPBACK_OWNER","fixture-mail@naver.com")
    private fun scope()=MailScope(account,"PUBLIC_CASE","PUBLIC_PERSON",setOf("counterpart@example.invalid"),setOf("contract"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-12"),relationshipConfirmedByUser=true)
    private fun secret()=SecretChars(CharArray(16){'a'})
    private class Inbox:MailProposalInbox {var commits=0;var purges=0;override fun replace(batch:ScanBatch){commits++};override fun purge(accountKey:String){purges++}}
    private class Server:AutoCloseable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        private val executor=Executors.newCachedThreadPool()
        @Volatile private var closed=false
        @Volatile var validity=100L
        var readOnly=true
        val commands:MutableList<String> = Collections.synchronizedList(mutableListOf())
        var headerGate:CountDownLatch?=null;var headerReady:CountDownLatch?=null
        init {executor.submit {while(!closed)try{val client=socket.accept();executor.submit{serve(client)}}catch(_:IOException){if(!closed)throw AssertionError("Unexpected loopback accept failure")}}}
        fun mailbox(account:AccountBinding)=object:ReadOnlyMailbox {
            override val origin=Origin.SYNTHETIC_FIXTURE
            override fun open(selected:AccountBinding,secret:SecretChars):ReadOnlyMailbox.Session {
                require(selected==account)
                val c=Socket(InetAddress.getByName("127.0.0.1"),socket.localPort).apply{soTimeout=4000}
                return ImapSession(account,c.getInputStream(),c.getOutputStream(),origin){c.close()}.also{it.login(secret)}
            }
        }
        private fun serve(client:Socket) {
            client.use { c ->
                c.soTimeout=4000;val output=c.getOutputStream();val reader=c.getInputStream().bufferedReader(Charsets.US_ASCII)
                fun send(s:String){output.write(s.toByteArray(Charsets.US_ASCII));output.flush()}
                try {
                    send("* OK PUBLIC_LOOPBACK\r\n")
                    while(!closed) {
                        val line=reader.readLine() ?: break;val tag=line.substringBefore(' ');val request=line.substringAfter(' ')
                        when {
                            request.startsWith("LOGIN ")-> {commands+="LOGIN REDACTED";send("$tag OK authenticated fixture\r\n")}
                            request=="EXAMINE INBOX"-> {commands+=request;send("* OK [UIDVALIDITY $validity]\r\n$tag OK [${if(readOnly)"READ-ONLY" else "READ-WRITE"}] examined\r\n")}
                            request.startsWith("UID SEARCH ")-> {commands+="UID SEARCH BOUNDED_SCOPE";send("* SEARCH 1 2 3\r\n$tag OK searched\r\n")}
                            request.startsWith("UID FETCH ")&&request.contains("HEADER.FIELDS")-> {
                                commands+="UID FETCH BODY.PEEK[HEADER]";headerReady?.countDown();headerGate?.await(3,TimeUnit.SECONDS)
                                val uid=request.substringAfter("UID FETCH ").substringBefore(' ').toLong()
                                val from=if(uid==2L)"lookalike@example.invalid" else "counterpart@example.invalid"
                                val subject=if(uid==3L)"different work" else "contract docs"
                                val h="From: $from\r\nTo: fixture-mail@naver.com\r\nSubject: $subject\r\nContent-Type: text/plain; charset=US-ASCII\r\n\r\n"
                                send("* 1 FETCH (UID $uid INTERNALDATE \"10-Oct-2026 10:00:00 +0000\" BODY[HEADER.FIELDS (FROM TO SUBJECT)]<0> {${h.length}}\r\n$h)\r\n$tag OK header\r\n")
                            }
                            request.startsWith("UID FETCH ")&&request.contains("BODY.PEEK[TEXT]")-> {
                                commands+="UID FETCH BODY.PEEK[TEXT]";val uid=request.substringAfter("UID FETCH ").substringBefore(' ').toLong();val body="PUBLIC_LOOPBACK_SELECTED_BODY"
                                send("* 1 FETCH (UID $uid BODY[TEXT]<0> {${body.length}}\r\n$body)\r\n$tag OK body\r\n")
                            }
                            else->throw AssertionError("Unexpected mutating or unsupported IMAP command")
                        }
                    }
                }catch(_:IOException){ /* The test deliberately closes sockets during cancellation. */ }
            }
        }
        override fun close(){closed=true;socket.close();headerGate?.countDown();executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun L01BoundedExactCounterpartHeadersAndSelectedBodyAreReadOnly() {
        Server().use { server->val inbox=Inbox();val intake=MailIntake(AccountActionPolicy.denied(),server.mailbox(account),inbox);val ticket=intake.select(scope());val headers=intake.scan(ticket,secret())
            assertEquals(2,headers.candidates.size);assertTrue(headers.selectedBodies.isEmpty())
            val selected=headers.candidates.single{it.relation==Relation.CANDIDATE}.header.ref
            val body=intake.readSelected(headers,setOf(selected),secret());assertEquals("PUBLIC_LOOPBACK_SELECTED_BODY",body.selectedBodies.getValue(selected).text);assertEquals(2,inbox.commits)
            assertEquals(1,server.commands.count{it=="UID FETCH BODY.PEEK[TEXT]"});assertTrue(server.commands.contains("EXAMINE INBOX"));assertFalse(server.commands.any{Regex("\\b(SELECT|STORE|MOVE|COPY|DELETE|EXPUNGE|APPEND)\\b").containsMatchIn(it)})
        }
    }
    @Test fun L02ReadWriteServerResponseCannotCommitHeaderCandidates() {
        Server().use { server->server.readOnly=false;val inbox=Inbox();val intake=MailIntake(AccountActionPolicy.denied(),server.mailbox(account),inbox);val ticket=intake.select(scope())
            try{intake.scan(ticket,secret());fail()}catch(e:MailFailure){assertEquals(MailError.INVALID_RESPONSE,e.code)}
            assertEquals(0,inbox.commits);assertFalse(server.commands.any{it.startsWith("UID FETCH")})
        }
    }
    @Test fun L03UidValidityChangeStopsBodyBeforeFetch() {
        Server().use { server->val inbox=Inbox();val intake=MailIntake(AccountActionPolicy.denied(),server.mailbox(account),inbox);val ticket=intake.select(scope());val b=intake.scan(ticket,secret());server.validity=101
            try{intake.readSelected(b,setOf(b.candidates.first().header.ref),secret());fail()}catch(e:IllegalArgumentException){assertEquals("UIDVALIDITY_CHANGED_RESCAN_REQUIRED",e.message)}
            assertEquals(1,inbox.commits);assertFalse(server.commands.contains("UID FETCH BODY.PEEK[TEXT]"))
        }
    }
    @Test fun L04DisconnectWhileHeaderIsPendingHasNoLateCommit() {
        Server().use { server->val inbox=Inbox();val intake=MailIntake(AccountActionPolicy.denied(),server.mailbox(account),inbox);val ticket=intake.select(scope());server.headerReady=CountDownLatch(1);server.headerGate=CountDownLatch(1)
            val worker=Executors.newSingleThreadExecutor();try{val future=worker.submit<ScanBatch>{intake.scan(ticket,secret())};assertTrue(server.headerReady!!.await(3,TimeUnit.SECONDS));intake.disconnect(account);server.headerGate!!.countDown()
                try{future.get(5,TimeUnit.SECONDS);fail()}catch(e:ExecutionException){assertTrue(e.cause is MailFailure)};assertEquals(0,inbox.commits);assertEquals(1,inbox.purges)
            }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
        }
    }
}
