package org.payswap.camscan.capture.session

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.payswap.camscan.R
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.BitmapImageAdapter
import org.payswap.camscan.processing.CornerF
import org.payswap.camscan.processing.Homography
import org.payswap.camscan.processing.PointD
import org.payswap.camscan.processing.QualityFlag
import org.payswap.camscan.processing.QualityGates
import org.payswap.camscan.processing.QuadF

/*
 * CAMSCAN-PROD-004 §6.5 — the post-capture review surface.
 *
 * Contract-frozen semantic ids (fragment_review.xml) — ADB parity tests
 * depend on them; do not rename:
 *   review_page_image, review_accept_button, review_retake_button,
 *   review_crop_button, review_crop_sheet, review_rotate_button,
 *   review_enhancement_group (with review_enhancement_original /
 *   _grayscale / _black_and_white / _contrast / _sharpen / _low_light),
 *   review_quality_banner.
 *
 * Behavior (the contract's Review section: inspect / accept / retake /
 * crop-adjust / rotate / change enhancement):
 *  - shows the current page's processed image with the rotation APPLIED
 *    (rotation is render metadata; the processed bytes stay un-rotated);
 *  - accept pops back to the scan surface — the page stays in the session;
 *  - retake arms the pending retake index and pops back; the NEXT capture
 *    atomically replaces this page (the old page survives if the user
 *    never captures again);
 *  - crop-adjust opens review_crop_sheet: the CropAdjustView emits an
 *    adjusted quad in PROCESSED space; the fragment maps it back into
 *    SOURCE space through the retained output->source homography
 *    (recomputed from ProcessedGeometry — the same transform the PROD-003
 *    corrector solved) and re-runs the pipeline from the SOURCE capture;
 *  - rotate cycles 0/90/180/270 (metadata; no re-processing);
 *  - the enhancement row re-processes from the source on the default
 *    dispatcher (deterministic);
 *  - the quality banner surfaces the PROD-003 QualityGates advisory flags.
 *
 * Degradation (never a crash): a lost session token (process death) or a
 * vanished page pops this surface back honestly.
 */
class ReviewFragment : Fragment(R.layout.fragment_review) {

    private var controller: ScanSessionController? = null
    private var pageIndex = INDEX_NONE
    private var renderJob: Job? = null
    private var suppressEnhancementListener = false

    private var pageImage: ImageView? = null
    private var qualityBanner: TextView? = null
    private var enhancementGroup: RadioGroup? = null
    private var cropSheet: View? = null
    private var cropSurface: CropAdjustView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments
        pageIndex = args?.getInt(ARG_PAGE_INDEX, INDEX_NONE) ?: INDEX_NONE
        controller = ScanSessionRegistry.get(args?.getString(ARG_SESSION_TOKEN))
        if (controller == null || pageIndex == INDEX_NONE) {
            // Registry lost (process death) or bad arguments — degrade honestly.
            parentFragmentManager.popBackStack()
            return
        }

        pageImage = view.findViewById(R.id.review_page_image)
        qualityBanner = view.findViewById(R.id.review_quality_banner)
        enhancementGroup = view.findViewById(R.id.review_enhancement_group)
        cropSheet = view.findViewById(R.id.review_crop_sheet)
        cropSurface = view.findViewById(R.id.review_crop_surface)

        view.findViewById<Button>(R.id.review_accept_button)?.setOnClickListener { popBackToScan() }
        view.findViewById<Button>(R.id.review_retake_button)?.setOnClickListener {
            controller?.markRetake(pageIndex)
            popBackToScan()
        }
        view.findViewById<Button>(R.id.review_rotate_button)?.setOnClickListener { rotateCurrent() }
        view.findViewById<Button>(R.id.review_crop_button)?.setOnClickListener { openCropSheet() }
        view.findViewById<Button>(R.id.review_crop_cancel_button)?.setOnClickListener {
            cropSheet?.visibility = View.GONE
        }
        view.findViewById<Button>(R.id.review_crop_apply_button)?.setOnClickListener { applyCrop() }

        enhancementGroup?.setOnCheckedChangeListener { _, checkedId ->
            if (suppressEnhancementListener) return@setOnCheckedChangeListener
            val mode = modeFor(checkedId) ?: return@setOnCheckedChangeListener
            enhanceCurrent(mode)
        }

        renderCurrent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderJob = null
        pageImage = null
        qualityBanner = null
        enhancementGroup = null
        cropSheet = null
        cropSurface = null
    }

    // ------------------------------------------------------------------ actions

    private fun popBackToScan() {
        parentFragmentManager.popBackStack()
    }

    private fun rotateCurrent() {
        val ctrl = controller ?: return
        val page = ctrl.pageAt(pageIndex) ?: run { popBackToScan(); return }
        val next = (page.rotationDegrees + 90) % FULL_ROTATION
        if (ctrl.setRotation(pageIndex, next)) {
            renderCurrent()
        }
    }

    private fun enhanceCurrent(mode: PageEnhancementMode) {
        val ctrl = controller ?: return
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val applied = withContext(Dispatchers.Default) { ctrl.setEnhancement(pageIndex, mode) }
            if (applied) {
                renderCurrent()
            } else {
                showFailure(R.string.review_reprocess_failed)
                renderCurrent()
            }
        }
    }

    private fun openCropSheet() {
        val page = controller?.pageAt(pageIndex) ?: run { popBackToScan(); return }
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                BitmapImageAdapter.toBitmap(page.processed.buffer)
            }
            cropSurface?.setBitmap(bitmap)
            cropSheet?.visibility = View.VISIBLE
        }
    }

    private fun applyCrop() {
        val ctrl = controller
        val surface = cropSurface
        if (ctrl == null || surface == null) return
        val processedQuad = surface.currentQuadInBitmapSpace()
        val page = ctrl.pageAt(pageIndex)
        if (processedQuad == null || page == null) {
            cropSheet?.visibility = View.GONE
            return
        }

        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val applied = withContext(Dispatchers.Default) {
                mapBackToSourceSpace(page, processedQuad)?.let { sourceQuad ->
                    ctrl.adjustCrop(pageIndex, sourceQuad)
                } ?: false
            }
            cropSheet?.visibility = View.GONE
            if (applied) {
                renderCurrent()
            } else {
                showFailure(R.string.review_reprocess_failed)
                renderCurrent()
            }
        }
    }

    /**
     * Maps an adjusted quad from PROCESSED-pixel space back into SOURCE-pixel
     * space: recompute the output-rectangle -> sourceQuad homography from
     * the retained [org.payswap.camscan.processing.ProcessedGeometry] (the
     * same transform the PROD-003 corrector solved), then forward-map the
     * adjusted corners. Null when the geometry is degenerate.
     */
    private fun mapBackToSourceSpace(page: SessionPage, quad: QuadF): QuadF? {
        val geometry = page.processed.geometry
        val width = geometry.outputWidth.toDouble()
        val height = geometry.outputHeight.toDouble()
        val homography = Homography.solve(
            listOf(
                PointD(0.0, 0.0),
                PointD(width, 0.0),
                PointD(width, height),
                PointD(0.0, height),
            ),
            geometry.sourceQuad.corners.map { PointD(it.x.toDouble(), it.y.toDouble()) },
        ) ?: return null

        val mapped = quad.corners.map { corner ->
            val point = homography.map(corner.x.toDouble(), corner.y.toDouble())
            CornerF(point.x.toFloat(), point.y.toFloat())
        }
        return QuadF(mapped[0], mapped[1], mapped[2], mapped[3])
    }

    // ------------------------------------------------------------------ rendering

    private fun renderCurrent() {
        val ctrl = controller ?: return
        val page = ctrl.pageAt(pageIndex) ?: run { popBackToScan(); return }
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val (bitmap, warnings) = withContext(Dispatchers.Default) {
                val base = BitmapImageAdapter.toBitmap(page.processed.buffer)
                val rotated = rotateBitmap(base, page.rotationDegrees)
                val flags = QualityGates().evaluate(page.processed.buffer).flags
                rotated to buildList {
                    if (QualityFlag.BLURRED in flags) add(R.string.review_quality_blurred)
                    if (QualityFlag.GLARE in flags) add(R.string.review_quality_glare)
                    if (QualityFlag.TOO_DARK in flags) add(R.string.review_quality_dark)
                    if (QualityFlag.LOW_CONTRAST in flags) add(R.string.review_quality_low_contrast)
                }
            }
            pageImage?.setImageBitmap(bitmap)
            renderBanner(warnings)
            suppressEnhancementListener = true
            enhancementGroup?.check(buttonFor(page.enhancement))
            suppressEnhancementListener = false
        }
    }

    private fun renderBanner(warnings: List<Int>) {
        val banner = qualityBanner ?: return
        if (warnings.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        val text = warnings.joinToString(SEPARATOR) { getString(it) }
        banner.text = text
        banner.contentDescription = text
        banner.visibility = View.VISIBLE
    }

    private fun showFailure(messageRes: Int) {
        val appContext = context ?: return
        Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % FULL_ROTATION == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun buttonFor(mode: PageEnhancementMode): Int = when (mode) {
        PageEnhancementMode.ORIGINAL -> R.id.review_enhancement_original
        PageEnhancementMode.GRAYSCALE -> R.id.review_enhancement_grayscale
        PageEnhancementMode.BLACK_AND_WHITE -> R.id.review_enhancement_black_and_white
        PageEnhancementMode.CONTRAST -> R.id.review_enhancement_contrast
        PageEnhancementMode.SHARPEN -> R.id.review_enhancement_sharpen
        PageEnhancementMode.LOW_LIGHT -> R.id.review_enhancement_low_light
    }

    private fun modeFor(checkedId: Int): PageEnhancementMode? = when (checkedId) {
        R.id.review_enhancement_original -> PageEnhancementMode.ORIGINAL
        R.id.review_enhancement_grayscale -> PageEnhancementMode.GRAYSCALE
        R.id.review_enhancement_black_and_white -> PageEnhancementMode.BLACK_AND_WHITE
        R.id.review_enhancement_contrast -> PageEnhancementMode.CONTRAST
        R.id.review_enhancement_sharpen -> PageEnhancementMode.SHARPEN
        R.id.review_enhancement_low_light -> PageEnhancementMode.LOW_LIGHT
        else -> null
    }

    companion object {
        const val ARG_SESSION_TOKEN = "review_session_token"
        const val ARG_PAGE_INDEX = "review_page_index"

        private const val INDEX_NONE = -1
        private const val FULL_ROTATION = 360
        private const val SEPARATOR = "  ·  "

        /** Builds the review fragment for [token]'s page at [pageIndex]. */
        fun newInstance(token: String, pageIndex: Int): ReviewFragment = ReviewFragment().apply {
            arguments = bundleOf(
                ARG_SESSION_TOKEN to token,
                ARG_PAGE_INDEX to pageIndex,
            )
        }
    }
}
