package kr.callbrief

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Synthetic provider protocol only. Never relaxes the production isChildDocument guard. */
internal object SyntheticTreeMembershipSupport {
    fun response(method: String, extras: Bundle?, knownDocuments: Set<String>, allowed: Boolean = true): Bundle? {
        if(method != "android:isChildDocument") return null
        val parent = extras?.getParcelable<Uri>("uri")
        val target = extras?.getParcelable<Uri>("android.content.extra.TARGET_URI")
        val known = runCatching {
            parent != null && target != null && parent.authority == target.authority &&
                DocumentsContract.getTreeDocumentId(parent) == DocumentsContract.getTreeDocumentId(target) &&
                DocumentsContract.getDocumentId(parent) == DocumentsContract.getTreeDocumentId(parent) &&
                DocumentsContract.getDocumentId(target) != DocumentsContract.getDocumentId(parent) &&
                DocumentsContract.getDocumentId(target) in knownDocuments
        }.getOrDefault(false)
        return Bundle().apply { putBoolean("result", allowed && known) }
    }
}
