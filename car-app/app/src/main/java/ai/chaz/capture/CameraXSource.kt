package ai.chaz.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 카메라 한 대 (CameraX). 차량 카메라(AUTOMOTIVE_LOCATION)와 에뮬레이터 웹캠 모두 이것으로 연다.
 * 카메라는 앱 프로세스 수명(ProcessLifecycleOwner)에 묶는다 → 앱이 백그라운드로 가면 자동으로 닫히고,
 * 미리보기 화면이 없어도(주행 중) ImageCapture 는 동작한다.
 */
class CameraXSource(
    private val appContext: Context,
    val cameraId: String,
    override val role: CameraRole,
    override val kind: SourceKind,
    override val label: String,
) : CameraSource {

    private val preview = Preview.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null

    @OptIn(ExperimentalCamera2Interop::class)
    private val selector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }
        .build()

    override fun start() {
        if (provider != null) return
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            val p = future.get()
            try {
                p.unbind(preview, imageCapture)
                p.bindToLifecycle(ProcessLifecycleOwner.get(), selector, preview, imageCapture)
                provider = p
                previewView?.let { preview.surfaceProvider = it.surfaceProvider }
            } catch (e: Exception) {
                Log.w(TAG, "카메라 $cameraId 바인딩 실패: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    override fun stop() {
        provider?.unbind(preview, imageCapture)
        provider = null
    }

    override fun createPreview(context: Context, owner: LifecycleOwner): View {
        val view = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = view
        preview.surfaceProvider = view.surfaceProvider
        start()
        return view
    }

    override fun releasePreview(view: View) {
        // 미리보기 위치가 바뀌면 새 View 가 먼저 붙고 옛 View 가 나중에 풀릴 수 있다 → 현재 View 일 때만 끊는다
        if (view !== previewView) return
        preview.surfaceProvider = null
        previewView = null
    }

    override suspend fun capture(): Bitmap {
        start()
        return suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(ContextCompat.getMainExecutor(appContext), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = image.use { it.toBitmap().rotate(it.imageInfo.rotationDegrees) }
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
        }
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap =
        if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    companion object { private const val TAG = "ChazCamera" }
}
