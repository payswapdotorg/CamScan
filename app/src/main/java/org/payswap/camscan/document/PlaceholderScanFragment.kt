package org.payswap.camscan.document

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import org.payswap.camscan.R

/**

Placeholder scan surface (CAMSCAN-PROD-005): stamped notice marking where

Worker 1's capture surface (CAMSCAN-PROD-001) attaches at integration.

No scanning happens here; Close pops back to Home without faking an

onScanFinished(documentId) callback.
*/
class PlaceholderScanFragment : Fragment(R.layout.fragment_placeholder_scan) {

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
view.findViewById<MaterialButton>(R.id.placeholder_scan_close).apply {
contentDescription = context.getString(R.string.workspace_placeholder_scan_close_cd)
setOnClickListener { parentFragmentManager.popBackStack() }
}
}

}
