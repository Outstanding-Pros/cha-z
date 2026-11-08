package ai.chaz.platform

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ProbeResult {
    val reason: String

    /** 사용 가능. */
    data class Ok(override val reason: String) : ProbeResult

    /** 시도했지만 사용 불가. */
    data class Fail(override val reason: String) : ProbeResult

    /** 아직 구현·포함하지 않은 후보 (예: "T07에서 포함"). 건너뛴다. */
    data class Pending(override val reason: String) : ProbeResult
}

/** 기능 하나의 구현 후보. id 는 어댑터 팩토리가 구현체를 고를 때 쓰는 키. */
class Candidate(
    val id: String,
    val label: String,
    val tier: Tier,
    val probe: suspend () -> ProbeResult,
)

/**
 * 앱 시작 시 기능별 후보를 위에서부터 시도해 SdkAvailability 에 기록한다.
 * 실행 중 같은 기능이 3회 연속 실패하면 다음 후보로 내려간다.
 */
class CapabilityProbe(
    private val availability: SdkAvailability,
    private val chains: Map<Feature, List<Candidate>>,
    private val timeoutMs: Long = 6_000,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val failures = mutableMapOf<Feature, Int>()
    private val lock = Mutex()

    suspend fun probeAll() = coroutineScope {
        chains.keys.forEach { feature -> launch { probe(feature) } }
    }

    suspend fun probe(feature: Feature, demoted: Set<String> = emptySet(), note: String? = null): Selection {
        val chain = chains[feature].orEmpty()
        val attempts = mutableListOf<Attempt>()
        var selected: Attempt? = null
        for (c in chain) {
            if (selected != null) {
                attempts += Attempt(c.id, c.label, c.tier, AttemptStatus.SKIPPED, "상위 후보 선택됨")
                continue
            }
            if (c.id in demoted) {
                attempts += Attempt(c.id, c.label, c.tier, AttemptStatus.FAILED, "런타임 3회 연속 실패로 제외")
                continue
            }
            val result = runCandidate(c)
            val attempt = Attempt(
                c.id, c.label, c.tier,
                when (result) {
                    is ProbeResult.Ok -> AttemptStatus.OK
                    is ProbeResult.Fail -> AttemptStatus.FAILED
                    is ProbeResult.Pending -> AttemptStatus.PENDING
                },
                result.reason,
            )
            attempts += attempt
            if (result is ProbeResult.Ok) selected = attempt
        }
        val selection = Selection(feature, selected, attempts, demoted, note)
        availability.record(selection)
        log("${feature.name} → ${selected?.candidateId ?: "없음"} | " +
            attempts.joinToString(" / ") { "${it.candidateId}:${it.status}(${it.reason})" })
        return selection
    }

    private suspend fun runCandidate(c: Candidate): ProbeResult = try {
        withTimeoutOrNull(timeoutMs) { c.probe() } ?: ProbeResult.Fail("응답 없음 (${timeoutMs / 1000}초 초과)")
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // NoClassDefFoundError 등 SDK 클래스 로딩 실패도 여기서 '사용 불가'로 처리
        ProbeResult.Fail("${t.javaClass.simpleName}: ${t.message ?: "-"}")
    }

    /** 어댑터가 호출 성공 시 알린다. */
    suspend fun reportSuccess(feature: Feature) = lock.withLock { failures[feature] = 0 }

    /**
     * 어댑터가 호출 실패(onFailed, 네트워크 끊김) 시 알린다.
     * 3회 연속이면 현재 후보를 제외하고 다시 판정한다. 내려갔으면 true.
     */
    suspend fun reportFailure(feature: Feature, reason: String): Boolean {
        val demote = lock.withLock {
            val n = (failures[feature] ?: 0) + 1
            failures[feature] = if (n >= FAILOVER_THRESHOLD) 0 else n
            n >= FAILOVER_THRESHOLD
        }
        if (!demote) return false
        val current = availability.selection(feature) ?: return false
        val currentId = current.selectedId ?: return false
        probe(feature, current.demoted + currentId, note = "$currentId 3회 연속 실패: $reason")
        return true
    }

    companion object {
        const val TAG = "ChazProbe"
        const val FAILOVER_THRESHOLD = 3
    }
}
