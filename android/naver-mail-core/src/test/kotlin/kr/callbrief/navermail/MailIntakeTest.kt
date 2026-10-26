package kr.callbrief.navermail

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** All email content here is synthetic. No DNS, account settings, credentials or real mailbox access. */
class MailIntakeTest {
    private val account=AccountBinding("fixture-owner","fixture-mail@naver.com")
    private fun scope(a:AccountBinding=account)=MailScope(a,"fixture-business","fixture-counterpart",setOf("counterpart@example.invalid"),setOf("계약"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-08"),relationshipConfirmedByUser=true)
    private fun secret():Pair<CharArray,SecretChars> { val b=CharArray(16){('a'.code+it%20).toChar()};return b to SecretChars(b) }
    private fun header(uid:Long=1,a:AccountBinding=account,subject:String="계약 자료",messageId:String?="<fixture@example.invalid>",sha:String="fixture-hash",origin:Origin=Origin.SYNTHETIC_FIXTURE)=HeaderEnvelope(MailRef(a.key,"INBOX",100,uid),Instant.parse("2026-10-03T10:00:00Z"),messageId,setOf("counterpart@example.invalid"),setOf(a.address),subject,sha,"text/plain",origin=origin)
    private class Inbox:MailProposalInbox {val batches=mutableListOf<ScanBatch>();val purged=mutableListOf<String>();override fun replace(batch:ScanBatch){batches+=batch};override fun purge(accountKey:String){purged+=accountKey}}
    private class Fake(var rows:List<HeaderEnvelope>,override val origin:Origin=Origin.SYNTHETIC_FIXTURE):ReadOnlyMailbox {
        var opened=0;var bodyReads=0;var hasMore=false;var validity=100L;var onHeader:(()->Unit)?=null
        var bodyFactory:(HeaderEnvelope)->PlainBody={PlainBody(it.ref,"진행상황: 검토 중\n필요서류: 신청서\n할일: 원문 기한 확인\n> 할일: 과거 인용",false,it.origin)}
        override fun open(account:AccountBinding,secret:SecretChars):ReadOnlyMailbox.Session {opened++;return object:ReadOnlyMailbox.Session{
            override fun examineInbox()=validity
            override fun search(scope:MailScope)=SearchResult(rows.map{it.ref.uid},hasMore)
            override fun header(ref:MailRef):HeaderEnvelope {onHeader?.invoke();return rows.first{it.ref.uid==ref.uid}}
            override fun selectedPlainBody(header:HeaderEnvelope):PlainBody {bodyReads++;return bodyFactory(header)}
            override fun close(){}
        }}
    }
    private fun failure(action:()->Unit):Exception {try{action();fail("Failure expected")}catch(e:Exception){return e};error("unreachable")}
    @Test fun deniedRealScanNeverOpensAccountAndWipesBuffer(){val f=Fake(listOf(header(origin=Origin.IMAP_REAL)),Origin.IMAP_REAL);val inbox=Inbox();val intake=MailIntake(AccountActionPolicy.denied(),f,inbox);val ticket=intake.select(scope());val (chars,s)=secret();val e=failure{intake.scan(ticket,s)};assertEquals(MailError.APPROVAL_REQUIRED,(e as MailFailure).code);assertEquals(0,f.opened);assertTrue(chars.all{it=='\u0000'});assertTrue(inbox.batches.isEmpty())}
    @Test fun syntheticScanRemainsSyntheticAndDoesNotReadBody(){val f=Fake(listOf(header()));val intake=MailIntake(AccountActionPolicy.denied(),f,Inbox());val batch=intake.scan(intake.select(scope()),secret().second);assertEquals(Origin.SYNTHETIC_FIXTURE,batch.origin);assertEquals(0,f.bodyReads);assertEquals(BodyState.NOT_SELECTED,batch.candidates.single().bodyState)}
    @Test fun exactCounterpartRejectsSubstringSearchFalsePositive(){val s=scope();val h=header().copy(from=setOf("counterpart@example.invalid.evil"));assertNull(MailIntake.candidate(h,s,mutableMapOf()))}
    @Test fun sameEmailDifferentBusinessIsUncertain(){val row=MailIntake.candidate(header(subject="다른 문의"),scope(),mutableMapOf())!!;assertEquals(Relation.UNCERTAIN,row.relation);assertTrue(row.uncertainties.any{it.contains("다른 업무")})}
    @Test fun boundedInternalDateExcludesOutsideMail(){assertNull(MailIntake.candidate(header().copy(internalDate=Instant.parse("2026-10-08T00:00:00Z"),mailboxDate=LocalDate.parse("2026-10-08")),scope(),mutableMapOf()))}
    @Test fun serverCalendarDateMatchesSearchRatherThanUtcDate(){val h=header().copy(internalDate=Instant.parse("2026-09-30T15:30:00Z"),mailboxDate=LocalDate.parse("2026-10-01"));assertNotNull(MailIntake.candidate(h,scope(),mutableMapOf()))}
    @Test fun messageIdCollisionWithDifferentHeadersIsNotDuplicate(){val seen=mutableMapOf<String,MailRef>();MailIntake.candidate(header(),scope(),seen);assertNull(MailIntake.candidate(header(2,sha="different"),scope(),seen)!!.duplicateOf)}
    @Test fun identicalHeadersRetainSeparateUidAsDuplicateCandidate(){val seen=mutableMapOf<String,MailRef>();val first=header();MailIntake.candidate(first,scope(),seen);val duplicate=MailIntake.candidate(header(2),scope(),seen)!!;assertEquals(first.ref,duplicate.duplicateOf);assertEquals(2L,duplicate.header.ref.uid)}
    @Test fun missingMessageIdDoesNotCollapseMessages(){val seen=mutableMapOf<String,MailRef>();MailIntake.candidate(header(messageId=null),scope(),seen);assertNull(MailIntake.candidate(header(2,messageId=null),scope(),seen)!!.duplicateOf)}
    @Test fun crossAccountResponseCannotEnterInbox(){val f=Fake(listOf(header(a=AccountBinding("fixture-owner","fixture-other@naver.com"))));val inbox=Inbox();val i=MailIntake(AccountActionPolicy.denied(),f,inbox);failure{i.scan(i.select(scope()),secret().second)};assertTrue(inbox.batches.isEmpty())}
    @Test fun crossOriginResponseCannotEnterInbox(){val f=Fake(listOf(header(origin=Origin.IMAP_REAL)));val inbox=Inbox();val i=MailIntake(AccountActionPolicy.denied(),f,inbox);failure{i.scan(i.select(scope()),secret().second)};assertTrue(inbox.batches.isEmpty())}
    @Test fun disconnectDuringHeaderReadDropsLateResult(){val f=Fake(listOf(header()));val inbox=Inbox();val i=MailIntake(AccountActionPolicy.denied(),f,inbox);val ticket=i.select(scope());f.onHeader={i.disconnect(account)};val e=failure{i.scan(ticket,secret().second)};assertEquals(MailError.CANCELLED,(e as MailFailure).code);assertTrue(inbox.batches.isEmpty());assertTrue(account.key in inbox.purged)}
    @Test fun accountSwitchRevokesOldTicket(){val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val ticket=i.select(scope());i.select(scope(AccountBinding("fixture-owner","fixture-other@naver.com")));failure{i.scan(ticket,secret().second)};assertEquals(0,f.opened)}
    @Test fun selectionCopiesMutableFilters(){val emails=mutableSetOf("counterpart@example.invalid");val s=scope().copy(correspondentAddresses=emails);val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val ticket=i.select(s);emails.clear();assertEquals(1,i.scan(ticket,secret().second).candidates.size)}
    @Test fun searchLimitIsEnforcedBeforeReadingAnyHeaders(){val f=Fake((1L..51L).map{header(it)});val inbox=Inbox();val i=MailIntake(AccountActionPolicy.denied(),f,inbox);failure{i.scan(i.select(scope()),secret().second)};assertTrue(inbox.batches.isEmpty())}
    @Test fun uidValidityChangeRequiresRescan(){val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val b=i.scan(i.select(scope()),secret().second);f.validity=101;failure{i.readSelected(b,setOf(b.candidates.single().header.ref),secret().second)};assertEquals(0,f.bodyReads)}
    @Test fun bodyNeedsExplicitUidSelection(){val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val b=i.scan(i.select(scope()),secret().second);failure{i.readSelected(b,emptySet(),secret().second)};assertEquals(0,f.bodyReads)}
    @Test fun forgedReceiptCannotReadUnscannedUid(){val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val b=i.scan(i.select(scope()),secret().second);val forged=b.copy(candidates=listOf(b.candidates.single().copy(header=header(900))));failure{i.readSelected(forged,setOf(forged.candidates.single().header.ref),secret().second)};assertEquals(0,f.bodyReads);assertEquals(1,f.opened)}
    @Test fun receiptCollectionsCannotBeMutatedToAddAnUnscannedUid(){val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val b=i.scan(i.select(scope()),secret().second);failure{(b.candidates as MutableList<MailCandidate>).add(b.candidates.single().copy(header=header(900)))};failure{(b.candidates.single().header.from as MutableSet<String>).add("other@example.invalid")};assertEquals(1,b.candidates.size)}
    @Test fun multipartIsUnprocessedAndNeverBodyFetched(){val f=Fake(listOf(header().copy(mimeType="multipart/mixed")));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val b=i.scan(i.select(scope()),secret().second);val result=i.readSelected(b,setOf(b.candidates.single().header.ref),secret().second);assertEquals(0,f.bodyReads);assertEquals(BodyState.MIME_UNPROCESSED,result.candidates.single().bodyState);assertTrue(result.coverageIncomplete)}
    @Test fun attachedPlainTextIsNotFetched(){assertFalse(MailIntake.plainEligible(header().copy(disposition="attachment; filename=fixture.txt")))}
    @Test fun inlineFileIsAlsoUnprocessed(){assertFalse(MailIntake.plainEligible(header().copy(disposition="inline; filename=fixture.txt")))}
    @Test fun proposalsCarryExactQuoteAndRequireReview(){val f=Fake(listOf(header()));val i=MailIntake(AccountActionPolicy.denied(),f,Inbox());val b=i.scan(i.select(scope()),secret().second);val row=i.readSelected(b,setOf(b.candidates.single().header.ref),secret().second).candidates.single();assertEquals(3,row.proposals.size);val text=f.bodyFactory(row.header).text;row.proposals.forEach{assertTrue(it.requiresUserConfirmation);assertEquals(it.evidence.quote,text.substring(it.evidence.start,it.evidence.end));assertEquals(Origin.SYNTHETIC_FIXTURE,it.evidence.origin)}}
    @Test fun wrongBodyIdentityCannotBeAccepted(){val f=Fake(listOf(header()));f.bodyFactory={PlainBody(it.ref.copy(uid=999),"할일: 위조",false,it.origin)};val inbox=Inbox();val i=MailIntake(AccountActionPolicy.denied(),f,inbox);val b=i.scan(i.select(scope()),secret().second);failure{i.readSelected(b,setOf(b.candidates.single().header.ref),secret().second)};assertEquals(1,inbox.batches.size)}
    @Test fun retryNeverRepeatsAuthenticationOrTlsFailure(){assertNull(MailRetry.delaySeconds(MailError.AUTH_REQUIRED,0));assertNull(MailRetry.delaySeconds(MailError.TLS_REJECTED,0));assertEquals(600L,MailRetry.delaySeconds(MailError.NETWORK,2));assertNull(MailRetry.delaySeconds(MailError.NETWORK,3))}
    @Test fun ownerNamespacesCannotCollide(){assertNotEquals(account.key,AccountBinding("different-owner",account.address).key)}
    @Test fun rejectsFullMailboxAndUnconfirmedRelationship(){failure{scope().copy(since=LocalDate.parse("2020-01-01"))};failure{scope().copy(mailboxes=setOf("INBOX","ALL"))};failure{scope().copy(relationshipConfirmedByUser=false)} }
}
