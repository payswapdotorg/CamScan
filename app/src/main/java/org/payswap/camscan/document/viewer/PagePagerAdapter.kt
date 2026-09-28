package org.payswap.camscan.document.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.payswap.camscan.R
import org.payswap.camscan.core.storage.ContentStore

/**

Pager adapter rendering each page's processed image (CAMSCAN-PROD-006).

Loads bytes from the ContentStore on a background executor, decodes, and

applies [ViewerPageItem.rotationDegrees] at render time (display only —

re-edit belongs to the scan-session review). A placeholder stays visible

until delivery; a per-holder bound-ref guard discards stale loads.
*/
class PagePagerAdapter(
private val contentStore: ContentStore,
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
@Volatile
private var boundRef: String? = null

fun cancelStale() {
boundRef = null
}

fun bind(item: ViewerPageItem, position: Int, pageCount: Int) {
val context = itemView.context
image.contentDescription = context.getString(
R.string.workspace_viewer_page_cd, position + 1, pageCount,
)
val ref = item.processedImageRef
boundRef = ref
placeholder.visibility = View.VISIBLE
image.setImageBitmap(null)
if (ref == null) {
placeholder.visibility = View.GONE
return
}
decodeExecutor.execute {
val bytes = contentStore.open(ref)
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
private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ViewerPageItem>() {
override fun areItemsTheSame(oldItem: ViewerPageItem, newItem: ViewerPageItem): Boolean =
oldItem.pageId == newItem.pageId

override fun areContentsTheSame(oldItem: ViewerPageItem, newItem: ViewerPageItem): Boolean =
oldItem == newItem
}
}

}
