package ai.chaz.state

import ai.chaz.data.CaptureConfig
import ai.chaz.data.Session
import ai.chaz.data.TriggerSource

/** SPEC '앱 상태와 동작 모델' — 6개 상태. */
sealed interface AppState {
    data object Standby : AppState
    data class Capturing(val session: Session, val shotsTaken: Int) : AppState
    data class Saved(val sessionId: String) : AppState      // 주행 중 편집 잠금
    data class Editing(val sessionId: String) : AppState    // 정차 중만
    data class Handoff(val sessionId: String) : AppState
    data class Delivered(val resultId: String) : AppState
}

sealed interface AppEvent {
    data class Trigger(val source: TriggerSource, val config: CaptureConfig) : AppEvent // T1~T4
    /** > 변경: 컷 카운터 갱신용으로 추가 (SPEC 반영). */
    data class ShotTaken(val shotsTaken: Int) : AppEvent
    data object SessionFinished : AppEvent
    /** 차 촬영이 끝나 결과를 앨범에 저장함 → 대기로 (편집은 편집 탭에서 프레임을 골라 시작한다, 11/12 b). */
    data object Archived : AppEvent
    /** 폰 직접 셔터처럼 차 촬영 없이 완성된 세션이 들어옴. 촬영 중에는 무시(호출 쪽이 끝날 때까지 기다린다). */
    data class Imported(val sessionId: String) : AppEvent
    data class DrivingChanged(val restricted: Boolean) : AppEvent // DrivingGate
    data object HandoffRequested : AppEvent
    data object SendToPhone : AppEvent
    data object Stop : AppEvent
}

/** 상태 + 현재 주행 여부. 전이는 reduce 하나로만 한다. */
data class AppModel(val state: AppState = AppState.Standby, val restricted: Boolean = false)

/**
 * 순수 전이 함수. Session 생성(id·시각)만 밖에서 주입받아 테스트에서 고정한다.
 * - Capturing 중에는 주행 여부와 상관없이 촬영을 이어간다.
 * - 세션이 끝나면 주행 중 → Saved(편집 잠금), 정차 중 → Editing.
 * - Editing 중 주행이 시작되면 Saved 로 돌아가고(편집 내용 임시 저장은 편집 화면 몫), Saved 에서 정차하면 Editing.
 */
class AppStateMachine(private val newSession: (TriggerSource, CaptureConfig) -> Session) {

    fun reduce(model: AppModel, event: AppEvent): AppModel {
        val s = model.state
        return when (event) {
            is AppEvent.DrivingChanged -> {
                val next = when {
                    s is AppState.Editing && event.restricted -> AppState.Saved(s.sessionId)
                    s is AppState.Saved && !event.restricted -> AppState.Editing(s.sessionId)
                    else -> s
                }
                model.copy(state = next, restricted = event.restricted)
            }
            is AppEvent.Trigger ->
                if (s is AppState.Capturing) model // 촬영 중 중복 트리거는 무시
                else model.copy(state = AppState.Capturing(newSession(event.source, event.config), 0))
            is AppEvent.ShotTaken ->
                if (s is AppState.Capturing) model.copy(state = s.copy(shotsTaken = event.shotsTaken)) else model
            AppEvent.SessionFinished ->
                if (s is AppState.Capturing) model.copy(state = finished(s.session.id, model.restricted)) else model
            AppEvent.Archived ->
                if (s is AppState.Capturing) model.copy(state = AppState.Standby) else model
            is AppEvent.Imported ->
                if (s is AppState.Capturing) model else model.copy(state = finished(event.sessionId, model.restricted))
            AppEvent.Stop -> when (s) {
                // 한 컷이라도 찍었으면 결과를 남긴다
                is AppState.Capturing ->
                    model.copy(state = if (s.shotsTaken > 0) finished(s.session.id, model.restricted) else AppState.Standby)
                is AppState.Editing, is AppState.Handoff, is AppState.Delivered -> model.copy(state = AppState.Standby)
                else -> model
            }
            AppEvent.HandoffRequested -> when (s) {
                is AppState.Saved -> model.copy(state = AppState.Handoff(s.sessionId))
                is AppState.Editing -> model.copy(state = AppState.Handoff(s.sessionId))
                else -> model
            }
            AppEvent.SendToPhone -> when (s) {
                // TODO(T10): 결과물 id 는 업로드 후 받는다. 지금은 세션 id 로 대신한다.
                is AppState.Saved -> model.copy(state = AppState.Delivered(s.sessionId))
                is AppState.Editing -> model.copy(state = AppState.Delivered(s.sessionId))
                is AppState.Handoff -> model.copy(state = AppState.Delivered(s.sessionId))
                else -> model
            }
        }
    }

    private fun finished(sessionId: String, restricted: Boolean): AppState =
        if (restricted) AppState.Saved(sessionId) else AppState.Editing(sessionId)
}
