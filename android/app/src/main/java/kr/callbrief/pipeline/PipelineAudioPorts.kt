package kr.callbrief.pipeline
internal interface PipelineSttModel { val modelSha256:String; val runtimeSha256:String; val modelName:String /* "ggml-large-v3-turbo-q5_0" */; fun verifiedModelFile():java.io.File? /* null when not READY */ }
internal interface PipelineAudioSource { /** Decoded 16 kHz mono float PCM chunks with their original start offset (ms) and total duration. */ fun decode(bytes:ByteArray, mime:String):PipelineDecodedAudio }
internal class PipelineDecodedAudio(val durationMs:Long, val chunks:List<Pair<Long,FloatArray>>, val vadApplied:Boolean)
