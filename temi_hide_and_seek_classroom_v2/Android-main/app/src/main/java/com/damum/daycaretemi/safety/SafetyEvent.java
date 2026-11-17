package com.damum.daycaretemi.safety;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 응급 관련 감지 이벤트. 출처(개발 패널, 소리 감지, 노트북 서버, 비상 버튼…)와 무관하게 같은 형식으로 {@link SafetyEventBus}에 올린다.
 * 응급 대응은 LLM을 거치지 않고 이 이벤트로 규칙 기반 즉시 실행한다.
 */
public final class SafetyEvent {

    public enum Type { CRY_DETECTED, EMERGENCY_BUTTON, FALL_DETECTED, DISMISS_VOICE }

    public enum Source { DEV_PANEL, SOUND, SERVER, BUTTON, VOICE }

    @NonNull public final Type type;
    @NonNull public final Source source;
    /** 발생 시각 (System.currentTimeMillis, 실제 시계 기준 — 부모 앱 "11:52 넘어짐 발생" 표시용) */
    public final long timestampMs;
    /** 발생 장소 (temi 저장 위치 이름 등). 모르면 null */
    @Nullable public final String location;
    /** 감지 신뢰도 0~1 (노트북 넘어짐 판정 등). 해당 없으면 null */
    @Nullable public final Float confidence;

    public SafetyEvent(@NonNull Type type, @NonNull Source source, long timestampMs,
                       @Nullable String location, @Nullable Float confidence) {
        this.type = type;
        this.source = source;
        this.timestampMs = timestampMs;
        this.location = location;
        this.confidence = confidence;
    }

    /** 현재 시각으로 생성 */
    public static SafetyEvent now(@NonNull Type type, @NonNull Source source,
                                  @Nullable String location, @Nullable Float confidence) {
        return new SafetyEvent(type, source, System.currentTimeMillis(), location, confidence);
    }

    public static SafetyEvent now(@NonNull Type type, @NonNull Source source) {
        return now(type, source, null, null);
    }

    /** "11:52:03" */
    public String timeText() {
        return new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(timestampMs));
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(timeText()).append(' ').append(type).append(" src=").append(source);
        if (location != null) sb.append(" loc=").append(location);
        if (confidence != null) sb.append(String.format(Locale.US, " conf=%.2f", confidence));
        return sb.toString();
    }
}
