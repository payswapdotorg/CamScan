package org.payswap.camscan.export.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import org.payswap.camscan.R
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.export.ExportArtifact

/**
 * Pure, android-free description of a single-artifact share intent
 * (CAMSCAN-PROD-007 §6.4). The JVM-testable [ShareIntents.buildShareSpec]
 * seam produces it; the android side only materializes the [Uri] and the
 * platform [Intent] from it.
 */
data class ShareSpec(
    /** Always [ShareIntents.ACTION_SEND] (equals `Intent.ACTION_SEND`). */
    val action: String,
    /** MIME type of the shared artifact. */
    val mime: String,
    /** Content URI of the shared bytes, as a string. */
    val streamUri: String,
    /** Whether receivers get temporary read access (FLAG_GRANT_READ_URI_PERMISSION). */
    val grantReadUriPermission: Boolean = true,
)

/**
 * Share-sheet integration (CAMSCAN-PROD-007 §6.4): resolves an
 * [ExportArtifact] from the store into a cache file served through the
 * export [ExportFileProvider], and builds the chooser intent for it.
 *
 * Pure part (JVM-testable, no android): [buildShareSpec] and [shareFlags].
 * Android part: [shareArtifact] — copies the artifact bytes into
 * `cacheDir/exports/` (the root declared in `res/xml/export_file_paths.xml`),
 * maps the file to a content URI via
 * [FileProvider.getUriForFile] on [EXPORT_PROVIDER_AUTHORITY], and wraps an
 * ACTION_SEND intent (type + EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION)
 * in a chooser titled from the workspace strings.
 *
 * `shareMultipleImages` (ACTION_SEND_MULTIPLE) belongs to a later work order
 * and is deliberately omitted.
 */
object ShareIntents {

    /**
     * FileProvider authority: the application id ("org.payswap.camscan") +
     * ".export.provider", matching the `<provider>` declaration the lead
     * lands in AndroidManifest.xml (the PROD-007 integration amendment).
     */
    const val EXPORT_PROVIDER_AUTHORITY = "org.payswap.camscan.export.provider"

    /** ACTION_SEND value, spelled out so the pure seam stays android-free. */
    const val ACTION_SEND = "android.intent.action.SEND"

    /** Cache subdirectory that the FileProvider paths XML declares. */
    const val EXPORT_CACHE_DIR = "exports"

    /** Pure construction seam: one artifact → its share request description. */
    fun buildShareSpec(mime: String, uri: String): ShareSpec =
        ShareSpec(
            action = ACTION_SEND,
            mime = mime,
            streamUri = uri,
            grantReadUriPermission = true,
        )

    /** Pure flag mapping for the spec (constant-only — JVM-testable). */
    fun shareFlags(spec: ShareSpec): Int =
        if (spec.grantReadUriPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0

    /**
     * Materializes [artifact] into a shareable chooser intent. Returns null
     * — an honest "cannot share" signal, never a throw — when the bytes are
     * missing from the store, the cache copy fails (I/O), or the provider
     * mapping fails (e.g. the manifest declaration is absent at a station
     * without the integration amendment).
     *
     * The caller (viewer fragment) owns `startActivity` so UI state stays
     * in one place.
     */
    suspend fun shareArtifact(
        context: Context,
        artifact: ExportArtifact,
        store: ContentStore,
    ): Intent? {
        val bytes = store.open(artifact.ref) ?: return null
        return try {
            val shareDir = File(context.cacheDir, EXPORT_CACHE_DIR)
            if (!shareDir.exists() && !shareDir.mkdirs()) return null
            val target = File(shareDir, artifact.displayName)
            target.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(context, EXPORT_PROVIDER_AUTHORITY, target)
            val spec = buildShareSpec(artifact.mime, uri.toString())
            val send = Intent(spec.action).apply {
                type = spec.mime
                putExtra(Intent.EXTRA_STREAM, Uri.parse(spec.streamUri))
                addFlags(shareFlags(spec))
            }
            Intent.createChooser(send, context.getString(R.string.workspace_share_chooser_title))
        } catch (expected: IOException) {
            null
        } catch (expected: IllegalArgumentException) {
            null
        }
    }
}
