package kr.callbrief.context

/** Portable candidate payload for a protected store/report adapter. Does not export bodies,
 * endpoints, grant flags or invent canonical task ids. Exact quotes stay in the protected
 * source ledger; references retain owner, original source version/hash and claim id. */
object CandidateJson {
    fun encode(state: ContextState, fences: Fences): String = json(mapOf(
        "schemaVersion" to 1,
        "mode" to "PROPOSAL_ONLY",
        "canonicalWriteAllowed" to false,
        "owner" to state.owner,
        "candidateRevision" to state.revision,
        "expectedFences" to mapOf("user" to fences.user, "lock" to fences.lock, "task" to fences.task,
            "board" to fences.board, "relation" to fences.relation, "archive" to fences.archive, "leaseEpoch" to fences.leaseEpoch),
        "items" to state.projection.items.entries.map { (key, item) -> mapOf(
            "key" to itemKey(key), "expectedItemRevision" to item.expectedItemRevision,
            "fields" to item.fields.entries.associate { (field, fact) -> field.name to mapOf(
                "value" to fact.value, "provenance" to fact.provenance.map(::claimRef).sortedBy(::json),
                "origins" to fact.origins.map(::claimRef).sortedBy(::json)) }) }.sortedBy(::json),
        "holds" to state.projection.holds.map { h -> mapOf("reason" to h.reason.name,
            "source" to h.source?.let(::sourceRef), "claim" to h.claim?.let(::claimRef),
            "item" to h.item?.let(::itemKey), "field" to h.field?.name) }.sortedBy(::json),
        "activeSources" to state.projection.activeSources.map(::sourceRef).sortedBy(::json),
    ))

    private fun sourceRef(ref: SourceRef): Map<String, Any> = mapOf("owner" to ref.key.source.owner,
        "channel" to ref.key.source.channel.name, "namespace" to ref.key.source.namespace,
        "externalId" to ref.key.source.externalId, "sourceVersion" to ref.key.version, "sha256" to ref.sha256)
    private fun claimRef(ref: ClaimRef): Map<String, Any> = mapOf("source" to sourceRef(ref.source), "claimId" to ref.claimId)
    private fun itemKey(key: ItemKey): Map<String, Any?> = mapOf("owner" to key.scope.owner,
        "personId" to key.scope.personId, "workId" to key.scope.workId, "companyId" to key.scope.companyId,
        "kind" to key.kind.name, "targetId" to key.targetId)

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c.code < 32 || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("Unsupported candidate JSON type")
    }
}
