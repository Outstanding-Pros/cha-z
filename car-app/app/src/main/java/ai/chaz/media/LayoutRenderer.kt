package ai.chaz.media

import ai.chaz.capture.CameraRole
import ai.chaz.capture.SourceKind
import ai.chaz.data.Cut
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** L1~L4 (SPEC F5). */
enum class Layout(val id: String, val label: String) {
    WINDOW_SWAP("L1", "차창 합성"),
    CUT_FRAME("L2", "컷형 프레임"),
    ROUTE_OVERLAY("L3", "여행 경로"),
    SURROUND_SEQUENCE("L4", "서라운드"),
}

enum class FrameColor(val label: String, val bg: Int, val fg: Int, val sub: Int) {
    /** 35mm 필름 스트립: 검은 띠 + 구멍 + 호박색 가장자리 글씨. L2 기본. */
    FILM("필름", 0xFF121110.toInt(), 0xFFF1E6D2.toInt(), 0xFFE0A040.toInt()),
    WHITE("화이트", 0xFFF7F2E8.toInt(), 0xFF26221E.toInt(), 0xFF8A8076.toInt()),
    BLACK("블랙", 0xFF0B0C0E.toInt(), 0xFFFFFFFF.toInt(), 0xFF9AA0A6.toInt()),
    OCEAN("오션", 0xFF1D6F8C.toInt(), 0xFFFFFFFF.toInt(), 0xFFCDEBF2.toInt()),
}

/** 자동 스탬프 (SPEC F5 공통 편집 5). 없는 값은 빼고 그린다. */
data class StampInfo(
    val place: String? = null,
    val dateText: String,
    val weather: String? = null,
    val driveMode: String? = null,
    val odometerKm: Double? = null,
)

data class RenderOptions(
    val stamp: StampInfo,
    val frameColor: FrameColor = FrameColor.FILM,
    val showStamp: Boolean = true,
    /** 색 필터 (미리보기에서 고른 값이 기본). 사진 부분에만 적용하고 프레임 색은 그대로 둔다. */
    val filter: ColorPreset = ColorPreset.ORIGINAL,
    /** L1: 실내 카메라의 정적 창문 마스크. */
    val windowMask: WindowMask? = null,
    /** 결과물에 쓴 소스 종류 → 시뮬레이션·AI 표기 (SPEC 구현 규칙 5). */
    val sourceKinds: Set<SourceKind> = emptySet(),
) {
    val simulationNote: String?
        get() = when {
            SourceKind.AI_GENERATED in sourceKinds -> "AI 생성 포함"
            SourceKind.RECORDED_VIDEO in sourceKinds -> "외부 영상 시뮬레이션"
            else -> null
        }
}

/** 한 시각의 실내·전방·후방 이미지 (라이브 미리보기 또는 저장된 컷). */
data class CutImages(val index: Int, val interior: Bitmap?, val front: Bitmap?, val rear: Bitmap?) {
    fun exterior(role: CameraRole): Bitmap? = when (role) {
        CameraRole.REAR -> rear ?: front
        else -> front ?: rear
    }

    companion object {
        fun load(cut: Cut, sampleSize: Int = 1): CutImages {
            fun read(role: CameraRole) = cut.frames.firstOrNull { it.role == role }?.path?.let { p ->
                BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
            return CutImages(cut.index, read(CameraRole.INTERIOR), read(CameraRole.FRONT), read(CameraRole.REAR))
        }
    }
}

/**
 * 레이아웃마다 하나. 같은 렌더러를 정차 중 라이브 미리보기(저해상, T09)와 최종 결과물(고해상)에 쓴다.
 * SPEC: renderPreview(live) / renderPhoto(cuts) / renderVideo(sessions) — 영상은 T14.
 */
interface LayoutRenderer {
    val layout: Layout
    /** 이 레이아웃에 필요한 컷 수 (L1 = 1, L2 = 2~4). */
    val maxCuts: Int
    suspend fun render(cuts: List<CutImages>, opts: RenderOptions, scale: Float = 1f): Bitmap
}

/** 창문 사각형 (원본 이미지 좌표, 시계 방향). */
data class WindowQuad(val name: String, val source: CameraRole, val points: List<Pair<Float, Float>>)

data class WindowMask(val imageWidth: Int, val imageHeight: Int, val windows: List<WindowQuad>) {
    companion object {
        fun parse(json: String): WindowMask {
            val j = JSONObject(json)
            val ws = j.getJSONArray("windows")
            return WindowMask(
                j.getInt("imageWidth"), j.getInt("imageHeight"),
                (0 until ws.length()).map { i ->
                    val w = ws.getJSONObject(i)
                    val q = w.getJSONArray("quad")
                    WindowQuad(
                        w.getString("name"),
                        CameraRole.valueOf(w.getString("source")),
                        (0 until q.length()).map { k -> q.getJSONArray(k).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } },
                    )
                },
            )
        }

        fun fromFile(f: File) = parse(f.readText())
    }
}
