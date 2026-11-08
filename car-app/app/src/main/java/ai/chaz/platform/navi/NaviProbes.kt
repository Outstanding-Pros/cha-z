package ai.chaz.platform.navi

import ai.chaz.platform.ProbeResult
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** LocationSource·RouteSource 후보 판정. 실제 소스 구현은 T12. */
object NaviProbes {
    private const val NAVI_SERVICE_PACKAGE = "ai.pleos.playground.service.navi"

    /**
     * NaviHelper: initialize() → addListener() → getCurrentLocationInfo() → removeListener() → release()
     * (CLAUDE.md 규칙 5). 현재 위치 콜백이 오면 사용 가능. 좌표는 로그에 남기지 않는다.
     */
    suspend fun probeNaviHelper(context: Context): ProbeResult {
        val installed = runCatching { context.packageManager.getPackageInfo(NAVI_SERVICE_PACKAGE, 0) }.isSuccess
        if (!installed) return ProbeResult.Fail("내비 서비스 패키지 없음")

        val helper = NaviHelper(context.applicationContext)
        val got = CompletableDeferred<Unit>()
        val listener = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo: ai.pleos.playground.navi.data.CurrentLocationInfo) {
                got.complete(Unit)
            }
        }
        return try {
            helper.initialize()
            helper.addListener(listener)
            helper.getCurrentLocationInfo()
            if (withTimeoutOrNull(4_000) { got.await() } != null) ProbeResult.Ok("현재 위치 콜백 수신")
            else ProbeResult.Fail("현재 위치 콜백 없음 (경로 미설정일 수 있음)")
        } finally {
            runCatching { helper.removeListener(listener) }
            runCatching { helper.release() }
        }
    }

    fun probeLocationManager(context: Context): ProbeResult {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return ProbeResult.Fail("위치 권한 없음")
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return ProbeResult.Fail("LocationManager 없음")
        val providers = lm.getProviders(true)
        return if (providers.isEmpty()) ProbeResult.Fail("활성 위치 제공자 없음")
        else ProbeResult.Ok("제공자: ${providers.joinToString()}")
    }
}
