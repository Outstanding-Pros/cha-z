package ai.chaz.capture

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.LifecycleOwner

/** 결과물 메타데이터 sourceKinds 에 남기는 소스 종류 (SPEC 도메인 모델). */
enum class SourceKind { VEHICLE_CAMERA, PHONE_CAMERA, WEBCAM, RECORDED_VIDEO, AI_GENERATED }

/**
 * 카메라 소스 어댑터. 실내·전방·후방 각각 하나씩 쓴다.
 * 미리보기는 정차 중에만 붙이고(createPreview/releasePreview), 촬영(capture)은 미리보기 없이도 동작해야 한다
 * — 주행 중에도 촬영은 계속하기 때문(SPEC F1).
 */
interface CameraSource {
    val role: CameraRole
    val kind: SourceKind
    /** 패널·칩에 보일 이름. 예: "웹캠 #0", "전방(시뮬레이션)". */
    val label: String
    /** 실제 차량 카메라가 아니면 true → 화면·결과물에 '시뮬레이션'/'AI 생성' 표기. */
    val simulated: Boolean get() = kind == SourceKind.RECORDED_VIDEO || kind == SourceKind.AI_GENERATED

    /** 소스를 연다 (카메라 바인딩 등). 여러 번 불려도 된다. */
    fun start()
    fun stop()

    /** 정차 중 미리보기용 View. 화면에서 빠지면 그 View 로 releasePreview(view). */
    fun createPreview(context: Context, owner: LifecycleOwner): View
    fun releasePreview(view: View)

    /**
     * 컷 index 를 inMs 뒤에 찍을 예정이라고 미리 알린다 (카운트다운 시작 무렵).
     * 원격 소스(폰)는 이걸로 셔터 순간에 맞춰 찍게 예약한다. 로컬 카메라는 할 일 없음.
     */
    fun scheduleShot(index: Int, inMs: Long) {}

    /** 지금 시각의 고해상도 스틸 (컷용). */
    suspend fun capture(): Bitmap

    /** 지금 시각의 저해상 프레임 (클립 스냅샷 시퀀스용, 빠르게). 아직 프레임이 없으면 null. */
    suspend fun snapshot(): Bitmap?
}

/** 테스트·미리보기용. 단색 비트맵을 돌려준다. */
class FakeCameraSource(
    override val role: CameraRole,
    override val kind: SourceKind = SourceKind.WEBCAM,
    override val label: String = "fake-${role.name.lowercase()}",
    private val color: Int = 0xFF3D8BFF.toInt(),
) : CameraSource {
    var started = false
        private set
    var captures = 0
        private set

    override fun start() { started = true }
    override fun stop() { started = false }
    override fun createPreview(context: Context, owner: LifecycleOwner): View = View(context).apply { setBackgroundColor(color) }
    override fun releasePreview(view: View) = Unit
    override suspend fun capture(): Bitmap {
        captures++
        return Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    }
    override suspend fun snapshot(): Bitmap? = capture()
}
