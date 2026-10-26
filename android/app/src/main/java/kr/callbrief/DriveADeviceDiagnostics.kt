package kr.callbrief

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.common.GoogleApiAvailability
import kr.callagent.drive.DriveRelay
import org.json.JSONObject
import java.security.MessageDigest

internal object DriveADeviceDiagnostics {
    fun inspect(context:Context):JSONObject {
        val pm=context.packageManager
        val info=pm.getPackageInfo(context.packageName,if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val certificates=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners.orEmpty() else @Suppress("DEPRECATION") info.signatures.orEmpty()
        val hashes=certificates.map { s ->JSONObject().put("sha1",MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}).put("sha256",DriveRelay.sha256(s.toByteArray())) }
        return JSONObject().put("package",context.packageName).put("versionCode",if(Build.VERSION.SDK_INT>=28)info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            .put("versionName",info.versionName).put("signingCertificates",org.json.JSONArray(hashes))
            .put("googleAndroidClientConfigured",BuildConfig.GOOGLE_ANDROID_CLIENT_ID.isNotBlank())
            .put("gmsLocalStatusCode",GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context))
            .put("currentConsolePackageSha1Match","NOT_VERIFIED").put("actualS25InstalledIdentity","NOT_VERIFIED")
            .put("currentDeviceGrant","NOT_VERIFIED").put("dotConnectorExactFileAccessAndWriter","NOT_VERIFIED").put("writerProvenance","NOT_VERIFIED").put("remoteAtomicCas","NOT_VERIFIED")
            .put("credentialsIncluded",false).put("newPermissionRequested",false).put("scope","LOCAL_INSTALLED_APK_GMS_DIAGNOSTIC_ONLY")
    }
}
