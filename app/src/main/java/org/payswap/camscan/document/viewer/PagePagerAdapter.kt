package org.payswap.camscan.document.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.payswap.camscan.R
import org.payswap.camscan.core.storage.ContentStore

/**

Pager adapter rendering each page's processed image (CAMSCAN-PROD-006).

Loads bytes from the ContentStore on a background executor, decodes, and

applies [ViewerPageItem.rotationDegrees] at render time (display only —

re-edit belongs to the scan-session review). A placeholder stays visible

until delivery; a per-holder bound-ref guard discards stale loads.
CAMSCAN-PROD-008: split SELECTION MODE — the injected [pageTapListener]

routes taps (set only while the viewer is in split mode) and

[selectedPageIds] is read live at bind time so the "Selected" badge and

dimmed image track the viewer's selection state (the viewer forces a

rebind when the selection changes; the differ cannot see it).
*/
class PagePagerAdapter(
private val contentStore: ContentStore,
private val pageTapListener: ((pageId: String) -> Unit)? = null,
private val selectedPageIds: () -> Set<String> = { emptySet() },
) : ListAdapter<ViewerPageItem, PagePagerAdapter.PageViewHolder>(DIFF_CALLBACK) {

private val decodeExecutor: ExecutorService = Executors.newFixedThreadPool(2)

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
val view = LayoutInflater.from(parent.context)
.inflate(R.layout.item_viewer_page, parent, false)
return PageViewHolder(view)
}

override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
val item = getItem(position)
holder.bind(item, position, itemCount)
}

override fun onViewRecycled(holder: PageViewHolder) {
holder.cancelStale()
super.onViewRecycled(holder)
}

inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

private val image: ImageView = itemView.findViewById(R.id.document_page_image)
private val placeholder: View = itemView.findViewById(R.id.document_page_loading)
private val selectedBadge: TextView = itemView.findViewById(R.id.document_split_selected_badge)
@Volatile
private var boundRef: String? = null

fun cancelStale() {
boundRef = null
}

fun bind(item: ViewerPageItem, position: Int, pageCount: Int) {
val context = itemView.context
val selected = item.pageId in selectedPageIds()
image.contentDescription = if (selected) {
context.getString(R.string.workspace_split_page_selected_cd, position + 1, pageCount)
} else {
context.getString(R.string.workspace_viewer_page_cd, position + 1, pageCount)
}
image.alpha = if (selected) SELECTED_IMAGE_ALPHA else 1f
selectedBadge.visibility = if (selected) View.VISIBLE else View.GONE
itemView.setOnClickListener { pageTapListener?.invoke(item.pageId) }
val ref = item.processedImageRef
boundRef = ref
placeholder.visibility = View.VISIBLE
image.setImageBitmap(null)
if (ref == null) {
placeholder.visibility = View.GONE
return
}
decodeExecutor.execute {
val bytes = runBlocking { contentStore.open(ref) }
val bitmap = bytes?.let { decodeRotated(it, item.rotationDegrees) }
itemView.post {
if (boundRef == ref) {
placeholder.visibility = View.GONE
if (bitmap != null) {
image.setImageBitmap(bitmap)
} else {
image.contentDescription =
context.getString(R.string.workspace_page_unavailable)
}
}
}
}
}

private fun decodeRotated(bytes: ByteArray, rotationDegrees: Int): Bitmap? {
val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
if (rotationDegrees % 360 == 0) return decoded
val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

 }

companion object {
private const val SELECTED_IMAGE_ALPHA = 0.45f

private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ViewerPageItem>() {
override fun areItemsTheSame(oldItem: ViewerPageItem, newItem: ViewerPageItem): Boolean =
oldItem.pageId == newItem.pageId

override fun areContentsTheSame(oldItem: ViewerPageItem, newItem: ViewerPageItem): Boolean =
oldItem == newItem
}
}

}
