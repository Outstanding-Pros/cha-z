package ai.chaz.sync

import ai.chaz.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * 차~즈 서버로 가는 모든 요청의 입구. 외부 공개(<서버 도메인>) 시 서버가 차량 전용·AI 경로에
 * 앱 토큰을 요구하므로 여기서 X-Chaz-Token 을 붙인다. 값은 local.properties(CHAZ_APP_TOKEN) 에서만.
 */
object ServerHttp {
    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        if (BuildConfig.CHAZ_APP_TOKEN.isNotEmpty()) setRequestProperty("X-Chaz-Token", BuildConfig.CHAZ_APP_TOKEN)
    }
}
