package kr.callbrief.core

import java.text.Normalizer

/** Metadata grammar only. No contact lookup, person/case assignment or audio access. */
data class ParsedContactBoundary(val alias: String?, val phones: Set<String>)
object ContactBoundary {
    private val extensions = setOf("m4a","mp4","mp3","mpeg","mpga","wav","webm","ogg","flac","aac","3gp","amr")
    private val recorder = Regex("^(?:통화[ _]*녹음|녹음|call(?:[ _]+recording)?)[ _.-]+",RegexOption.IGNORE_CASE)
    private val times = listOf(
        Regex("(?<![0-9])([0-9]{4})[-.]([0-9]{2})[-.]([0-9]{2})[T_ .-]([0-9]{2})[-.:]([0-9]{2})[-.:]([0-9]{2})(?![0-9])"),
        Regex("(?<![0-9])([0-9]{8})[_ T.-]([0-9]{6})(?![0-9])"),
        Regex("(?<![0-9])([0-9]{6})[_ T.-]([0-9]{6})(?![0-9])")
    )
    fun isAudioFilename(value:String) = AudioPolicy.extension(value) in extensions
    fun parse(filename:String):ParsedContactBoundary? {
        if (!isAudioFilename(filename) || filename.any { it=='/' || it=='\\' || it.isISOControl() }) return null
        val stem=Normalizer.normalize(filename.substringBeforeLast('.'),Normalizer.Form.NFKC).trim()
        val matches=times.flatMap { it.findAll(stem).toList() }.distinctBy { it.range }
        val timestamp=matches.singleOrNull()
        if(matches.isNotEmpty() && (timestamp==null || timestamp.range.last!=stem.lastIndex ||
                RecordingNames.startedAt(timestamp.value)==null || timestamp.range.first==0 ||
                stem[timestamp.range.first-1] !in " _.-")) return null
        var contact=if(timestamp!=null) stem.substring(0,timestamp.range.first).trimEnd(' ','_','-','.') else stem
        val prefix=recorder.find(contact)
        if(prefix!=null) contact=contact.substring(prefix.range.last+1).trim()
        if(contact.isBlank()) return null
        val phones=PrivacyRules.phoneTokens(contact)
        if(phones.size>1 || PrivacyRules.phoneTokenCount(contact)>1) return null
        val alias=PrivacyRules.removePhoneTokens(contact).trim().trim(' ','_','-','.','(',')','[',']').trim()
        // Any unaccounted digit might be an unparsed number/date or another field: hold it.
        if(alias.any { it.isDigit() }) return null
        val meaningful=alias.takeIf { PrivacyRules.normalizeAlias(it).isNotBlank() }
        // A contact name is explicit only in the recorder's name field; a bare whole phone is also supported.
        if(meaningful!=null && prefix==null) return null
        if(meaningful==null && phones.size!=1) return null
        return ParsedContactBoundary(meaningful,phones)
    }
}
