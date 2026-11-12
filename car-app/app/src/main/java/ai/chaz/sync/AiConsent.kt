package ai.chaz.sync

import android.content.Context

/** ✨ AI 오픈카(얼굴이 나온 사진을 외부 AI 로 전송) 사용 동의. 기기에만 저장, 설정에서 철회. */
object AiConsent {
    private const val KEY = "aiConsent"
    fun given(context: Context): Boolean = context.getSharedPreferences("chaz", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("chaz", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
