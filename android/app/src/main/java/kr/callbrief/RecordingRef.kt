package kr.callbrief

import android.net.Uri
import java.time.Instant

data class RecordingRef(val uri: Uri, val name: String, val size: Long, val mime: String?, val startedAt: Instant?, val modifiedAt: Long)
