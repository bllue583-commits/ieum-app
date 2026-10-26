package kr.callbrief

/** Published-v4 validated result canonical fingerprint, without storing trimmed or
 * normalized text. done is downgraded before hashing; applied always false. */
internal object SyntheticGenerationFingerprint {
    fun validate(value:Map<String,Any?>)=SyntheticTextContract.validateInput(value)
    fun hashValidated(value:Map<String,Any?>)=SyntheticTextContract.fingerprint(SyntheticTextContract.validatedReadback(value))
}
