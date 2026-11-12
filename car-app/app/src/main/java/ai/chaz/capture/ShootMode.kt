package ai.chaz.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 촬영 모드 (SPEC 11/12 변경). 실내 소스 판정(ProbeChains CAMERA_INTERIOR)에만 반영한다.
 * - DEMO(시연): 에뮬레이터 샘플 카메라 — 심사위원이 폰 없이 돌려 볼 때. 폰이 연결돼 있어도 샘플.
 * - LIVE(실촬영): 폰 컴패니언 카메라만 — 실제 사람을 찍을 때. 폰이 없으면 실내 소스 없음(연결 안내).
 */
enum class ShootMode(val label: String, val hint: String) {
    DEMO("시연", "실내 = 에뮬레이터 샘플 카메라"),
    LIVE("실촬영", "실내 = 폰 카메라 (폰에서 '실내 카메라로 쓰기')"),
}

object ShootModeStore {
    private const val KEY = "shootMode"
    private val _mode = MutableStateFlow<ShootMode?>(null)

    fun get(context: Context): ShootMode = _mode.value ?: load(context).also { _mode.value = it }

    fun flow(context: Context): StateFlow<ShootMode?> { get(context); return _mode.asStateFlow() }

    fun set(context: Context, mode: ShootMode) {
        context.getSharedPreferences("chaz", Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private fun load(context: Context): ShootMode =
        context.getSharedPreferences("chaz", Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { ShootMode.valueOf(it) }.getOrNull() } ?: ShootMode.DEMO
}
