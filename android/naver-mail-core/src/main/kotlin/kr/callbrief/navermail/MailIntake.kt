package kr.callbrief.navermail

/** Foreground only. Disconnect and scope changes revoke pending results before any inbox write. */
class MailIntake(private val policy: AccountActionPolicy, private val mailbox: ReadOnlyMailbox, private val inbox: MailProposalInbox) {
    private val lock = Any()
    private val runTag=java.util.UUID.randomUUID().toString()
    private var epoch = 0L
    private var scope: MailScope? = null
    private var issuedBatch:ScanBatch? = null
    private val sessions = mutableSetOf<ReadOnlyMailbox.Session>()
    fun select(value: MailScope): Long = synchronized(lock) {
        scope?.let { old -> if (old.fingerprint != value.fingerprint) inbox.purge(old.account.key) }
        revokeSessions(); issuedBatch=null; scope = value.copy(
            correspondentAddresses=java.util.Collections.unmodifiableSet(value.correspondentAddresses.toSet()),
            keywords=java.util.Collections.unmodifiableSet(value.keywords.toSet()),
            mailboxes=java.util.Collections.unmodifiableSet(value.mailboxes.toSet())); ++epoch
    }
    fun disconnect(account: AccountBinding) = synchronized(lock) {
        if (scope?.account?.key == account.key) { ++epoch; scope = null; issuedBatch=null; revokeSessions() }
        inbox.purge(account.key)
    }
    /** Stops in-flight work on leaving the foreground, while preserving already received inbox history. */
    fun stopForBackground()=synchronized(lock) { epoch++;scope=null;issuedBatch=null;revokeSessions() }
    private fun revokeSessions() { sessions.forEach { runCatching { it.close() } }; sessions.clear() }
    private fun valid(expected: MailScope, ticket: Long) {
        if (epoch != ticket || scope?.fingerprint != expected.fingerprint) throw MailFailure(MailError.CANCELLED)
    }
    fun scan(ticket: Long, secret: SecretChars): ScanBatch {
        try {
            if (mailbox.origin == Origin.IMAP_REAL) policy.requireApproved()
            val selected = synchronized(lock) { val s = scope ?: throw MailFailure(MailError.CANCELLED); valid(s, ticket); s }
            val session = mailbox.open(selected.account, secret)
            synchronized(lock) { try { valid(selected, ticket); sessions += session } catch(e: Exception) { session.close(); throw e } }
            try {
                val validity = session.examineInbox()
                synchronized(lock) { valid(selected, ticket) }
                val search = session.search(selected)
                require(search.uids.size <= selected.limit && search.uids.all { it > 0 }) { "UNBOUNDED_UIDS" }
                val seen = mutableMapOf<String, MailRef>()
                val rows = search.uids.distinct().map { uid ->
                    synchronized(lock) { valid(selected, ticket) }
                    val ref = MailRef(selected.account.key, "INBOX", validity, uid)
                    val header = session.header(ref).let { h -> h.copy(
                        from=java.util.Collections.unmodifiableSet(h.from.toSet()),to=java.util.Collections.unmodifiableSet(h.to.toSet())) }
                    require(header.ref == ref && header.origin == mailbox.origin) { "WRONG_ACCOUNT_OR_ORIGIN" }
                    candidate(header, selected, seen)
                }.filterNotNull()
                val batch = freeze(ScanBatch(selected.account, selected, ticket, mailbox.origin, rows,
                    search.hasMore || rows.any { it.header.headerTruncated },
                    listOf("사용자가 선택한 범위의 헤더 후보", "이름·전화번호만으로 사람/업무를 연결하지 않음", "첨부파일·다른 메일함·범위 밖 메일 미처리",
                        "${search.uids.distinct().size-rows.size}개 검색 결과는 정확한 주소·기간 확인 후 후보에서 제외"),intakeRunTag=runTag))
                synchronized(lock) { valid(selected, ticket); inbox.replace(batch);issuedBatch=batch }
                return batch
            } finally { synchronized(lock) { sessions -= session }; session.close() }
        } finally { secret.close() }
    }
    fun readSelected(batch: ScanBatch, refs: Set<MailRef>, secret: SecretChars): ScanBatch {
        try {
            if (mailbox.origin == Origin.IMAP_REAL) policy.requireApproved()
            synchronized(lock) { valid(batch.scope, batch.epoch);if(issuedBatch!=batch)throw MailFailure(MailError.CANCELLED) }
            require(batch.origin == mailbox.origin && batch.account == batch.scope.account)
            require(refs.isNotEmpty() && refs.size <= 10 && refs.all { ref -> batch.candidates.any { it.header.ref == ref } }) { "EXPLICIT_SELECTION_REQUIRED" }
            val session = mailbox.open(batch.account, secret)
            synchronized(lock) { try { valid(batch.scope, batch.epoch); sessions += session } catch(e: Exception) { session.close(); throw e } }
            try {
                val validity = session.examineInbox()
                require(refs.all { it.uidValidity == validity }) { "UIDVALIDITY_CHANGED_RESCAN_REQUIRED" }
                val bodies=batch.selectedBodies.toMutableMap()
                val updated = batch.candidates.map { row ->
                    if (row.header.ref !in refs) row else {
                        synchronized(lock) { valid(batch.scope, batch.epoch) }
                        if (!plainEligible(row.header)) row.copy(bodyState = BodyState.MIME_UNPROCESSED,
                            uncertainties = row.uncertainties + "멀티파트/HTML/첨부본문은 읽지 않았음")
                        else {
                            try {
                                val body = session.selectedPlainBody(row.header)
                                require(body.ref == row.header.ref && body.origin == row.header.origin && body.text.length <= 32768) { "WRONG_BODY_BINDING" }
                                bodies[body.ref]=body
                                row.copy(bodyState = if(body.truncated) BodyState.TRUNCATED else BodyState.PLAIN_AVAILABLE,
                                    proposals = propose(body), uncertainties = row.uncertainties + if(body.truncated) listOf("본문 일부만 읽음 · 나머지 미처리") else emptyList())
                            } catch(e:MailFailure) {
                                if(e.code!=MailError.UNSUPPORTED_MIME)throw e
                                row.copy(bodyState=BodyState.MIME_UNPROCESSED,uncertainties=row.uncertainties+"문자셋·본문 인코딩 미처리")
                            }
                        }
                    }
                }
                val next = freeze(batch.copy(candidates=updated, selectedBodies=bodies,coverageIncomplete=batch.coverageIncomplete || updated.any { it.bodyState in setOf(BodyState.TRUNCATED,BodyState.MIME_UNPROCESSED) }))
                synchronized(lock) { valid(batch.scope, batch.epoch);if(issuedBatch!=batch)throw MailFailure(MailError.CANCELLED);inbox.replace(next);issuedBatch=next }
                return next
            } finally { synchronized(lock) { sessions -= session }; session.close() }
        } finally { secret.close() }
    }
    companion object {
        private fun freeze(b:ScanBatch)=b.copy(
            candidates=java.util.Collections.unmodifiableList(b.candidates.map { row -> row.copy(
                reasons=java.util.Collections.unmodifiableList(row.reasons.toList()),
                uncertainties=java.util.Collections.unmodifiableList(row.uncertainties.toList()),
                proposals=java.util.Collections.unmodifiableList(row.proposals.toList())) }),
            notes=java.util.Collections.unmodifiableList(b.notes.toList()),selectedBodies=java.util.Collections.unmodifiableMap(b.selectedBodies.toMap()))
        fun plainEligible(h: HeaderEnvelope) = h.mimeType.equals("text/plain",true) && !h.disposition.contains("attachment",true) && !h.disposition.contains("filename",true) && !h.headerTruncated
        fun candidate(h: HeaderEnvelope, scope: MailScope, seen: MutableMap<String,MailRef>): MailCandidate? {
            require(h.ref.accountKey == scope.account.key && h.ref.mailbox in scope.mailboxes)
            val day = h.mailboxDate // Same calendar-day semantics as IMAP SINCE/BEFORE on INTERNALDATE.
            if (day < scope.since || day >= scope.before) return null
            val people = (h.from + h.to).intersect(scope.correspondentAddresses)
            // Server SEARCH is substring based. This exact local check rejects lookalike addresses.
            if (people.isEmpty()) return null
            val terms = scope.keywords.filter { h.subject.contains(it,true) }
            // Message-ID alone is not trusted. Only identical selected headers with the same ID are grouped.
            val identity = h.messageId?.let { digest(scope.account.key + "\u0000" + h.origin + "\u0000" + it + "\u0000" + h.rawHeaderSha) }
            val duplicate = identity?.let { seen[it] }
            if(identity != null && duplicate == null)seen[identity]=h.ref
            return MailCandidate(h,scope.fingerprint,if(terms.isEmpty()) Relation.UNCERTAIN else Relation.CANDIDATE,
                listOf("사용자가 확인한 이메일 정확 일치: ${people.joinToString()}") + terms.map { "제목의 업무 키워드: $it" },
                listOf("이메일·키워드 일치는 업무 사실 확정 근거가 아님", "발신자 위조·인용·누락·기한 확인 필요") +
                    (if(terms.isEmpty())listOf("업무 키워드 불일치 · 다른 업무일 가능성")else emptyList()) +
                    (if(duplicate!=null)listOf("동일 헤더 중복 후보 · 원자료 UID는 별도 보존")else emptyList()),
                if(plainEligible(h))BodyState.NOT_SELECTED else BodyState.ATTACHMENTS_UNPROCESSED,duplicate)
        }
        /** Local conservative extraction only; text is evidence, never an instruction to run tools. */
        fun propose(body: PlainBody): List<UpdateProposal> {
            val out=mutableListOf<UpdateProposal>(); var offset=0
            body.text.split('\n').forEach { line ->
                val kind = when {
                    line.startsWith("진행상황:") -> UpdateKind.PROGRESS
                    line.startsWith("필요서류:") -> UpdateKind.REQUIRED_DOCUMENT
                    line.startsWith("할일:") -> UpdateKind.TODO
                    else -> null
                }
                if(kind!=null && line.length in 5..300 && out.size<30)out+=UpdateProposal(kind,line,
                    Evidence(body.ref,body.origin,"plain-body",line,offset,offset+line.length,digest(body.text)))
                offset+=line.length+1
            }; return out
        }
    }
}
