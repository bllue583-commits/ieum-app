package kr.callbrief

internal object GenerationResultId {
    fun make(generation:String,suffix:String):String { SyntheticTextContract.uuid(generation);SyntheticTextContract.uuid(suffix);return "$generation:$suffix" }
    fun requireFor(generation:String,value:String) { require(value.length==73 && value.substringBefore(':')==generation);SyntheticTextContract.uuid(value.substringAfter(':'));SyntheticTextContract.uuid(generation) }
}
