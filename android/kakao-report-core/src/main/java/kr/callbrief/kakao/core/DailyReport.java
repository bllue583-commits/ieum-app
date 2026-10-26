package kr.callbrief.kakao.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Input supplied only after the host has finalized and reviewed its privacy-filtered summary. */
public final class DailyReport {
    public final String appUserKey;
    public final LocalDate date;
    public final ZoneId zone;
    public final String shortSummary;
    public final boolean finalized, synthetic;
    public DailyReport(String appUserKey, LocalDate date, ZoneId zone, String shortSummary, boolean finalized, boolean synthetic) {
        if(appUserKey==null || !appUserKey.matches("[A-Za-z0-9_-]{8,128}")) throw new IllegalArgumentException("opaque_app_user_key_required");
        Objects.requireNonNull(date); Objects.requireNonNull(zone); KakaoJson.validateUnicode(shortSummary);
        if(shortSummary.trim().isEmpty() || shortSummary.length()>16_384) throw new IllegalArgumentException("invalid_summary");
        this.appUserKey=appUserKey; this.date=date; this.zone=zone; this.shortSummary=shortSummary;
        this.finalized=finalized; this.synthetic=synthetic;
    }
    /** Exact privacy-filtered snapshot identity, including text beyond the excerpt. */
    public String fingerprint() {
        java.util.Map<String,Object> value=new java.util.LinkedHashMap<>();
        value.put("owner",appUserKey);value.put("date",date.toString());value.put("zone",zone.getId());
        value.put("summary",shortSummary);value.put("finalized",finalized);value.put("synthetic",synthetic);
        return DeliveryCoordinator.sha256(KakaoJson.encode(value));
    }
    @Override public String toString() { return "DailyReport[content withheld]"; }
}
