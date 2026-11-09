package ai.chaz.media

import ai.chaz.capture.CameraRole
import ai.chaz.capture.CameraSource
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** 미리보기에서 고른 레이아웃·필터 → 촬영 후 편집 화면의 기본값 (SPEC F1). layout = null 이면 원본 카메라. */
data class PreviewPrefs(val layout: Layout? = null, val filter: ColorPreset = ColorPreset.ORIGINAL)

/**
 * 프레임 시간으로 인물 분리를 켤지 정한다 (SPEC: 목표 15fps, 못 미치면 프레임만).
 * 최근 프레임 평균이 minFps 아래로 떨어지면 끄고, 끈 상태에서는 다시 켜지 않는다(깜빡임 방지).
 */
class FpsGovernor(private val minFps: Float = 6f, private val window: Int = 8, private val warmup: Int = 3) {
    private val times = ArrayDeque<Long>()
    private var seen = 0
    var segmentation = true
        private set

    fun record(frameMs: Long) {
        if (seen++ < warmup) return // 첫 프레임들은 모델 로딩이 섞여 느리다
        times.addLast(frameMs)
        if (times.size > window) times.removeFirst()
        if (segmentation && times.size == window && fps() < minFps) segmentation = false
    }

    fun fps(): Float = if (times.isEmpty()) 0f else 1000f / times.average().toFloat().coerceAtLeast(1f)
}

/**
 * 정차 중 라이브 미리보기 (T09). 실내·전방·후방 최신 프레임으로 같은 LayoutRenderer 를 저해상 반복 실행한다.
 * 수집(collect)하는 동안만 돈다 → 화면에서 빠지거나 주행 잠금으로 덮이면 바로 멈춘다.
 */
class LivePreviewEngine(
    private val sources: () -> List<CameraSource>,
    private val renderers: (Boolean) -> Map<Layout, LayoutRenderer>, // true = 인물 분리 사용
    private val options: suspend () -> RenderOptions,
) {
    data class Stats(val fps: Float, val segmentation: Boolean)

    private val _stats = MutableStateFlow(Stats(0f, true))
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun frames(layout: Layout, filter: ColorPreset): Flow<Bitmap> = flow {
        val gov = FpsGovernor()
        val base = options().copy(filter = filter, showStamp = true)
        val cams = sources()
        fun cam(role: CameraRole) = cams.firstOrNull { it.role == role }
        var warned = false
        Log.i(TAG, "미리보기 시작 ${layout.id} ${filter.label}")
        try { while (true) {
            val t0 = SystemClock.elapsedRealtime()
            val interior = cam(CameraRole.INTERIOR)?.snapshot()
            val front = cam(CameraRole.FRONT)?.snapshot()
            val rear = cam(CameraRole.REAR)?.snapshot()
            if (interior == null) { delay(100); continue } // 카메라가 아직 첫 프레임을 안 줌
            val renderer = renderers(gov.segmentation)[layout] ?: return@flow
            val n = if (layout == Layout.CUT_FRAME) 4 else 1
            val cuts = (1..n).map { CutImages(it, interior, front, rear) }
            val bmp = runCatching { renderer.render(cuts, base, PREVIEW_SCALE) }.getOrNull()
            if (bmp != null) emit(bmp)
            val dt = SystemClock.elapsedRealtime() - t0
            gov.record(dt)
            if (!gov.segmentation && !warned) { Log.i(TAG, "미리보기 ${"%.1f".format(gov.fps())}fps — 인물 분리 끄고 프레임만"); warned = true }
            _stats.value = Stats(gov.fps(), gov.segmentation)
            delay((TARGET_FRAME_MS - dt).coerceAtLeast(0))
        } } finally {
            Log.i(TAG, "미리보기 멈춤 ${layout.id}")
        }
    }

    companion object {
        private const val TAG = "ChazLive"
        const val TARGET_FRAME_MS = 66L // 15fps
        const val PREVIEW_SCALE = 0.6f
    }
}
