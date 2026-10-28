package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import java.time.LocalDateTime;

/**
 * 冲突检测设置。
 *
 * <p>两类记录：
 * <ul>
 *   <li>全局行：{@link #GLOBAL_ID}，kbId=null，存「全局总开关」；</li>
 *   <li>KB 行：id={@code kb:{kbId}}，kbId=xxx，存该 KB 的覆盖开关。</li>
 * </ul>
 * </p>
 */
public class ConflictSettings {

    public static final String GLOBAL_ID = "GLOBAL";
    private static final String KB_ID_PREFIX = "kb:";

    private final String id;
    private final String kbId;
    private final boolean autoEnabled;
    private final String updatedBy;
    private final LocalDateTime updatedAt;

    private ConflictSettings(Builder b) {
        this.id = b.id;
        this.kbId = b.kbId;
        this.autoEnabled = b.autoEnabled;
        this.updatedBy = b.updatedBy;
        this.updatedAt = b.updatedAt;
    }

    public static ConflictSettings globalDefault(boolean enabled) {
        return new Builder()
                .id(GLOBAL_ID).kbId(null).autoEnabled(enabled)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 构造/更新某 KB 的覆盖开关行。 */
    public static ConflictSettings forKb(String kbId, boolean enabled, String operator) {
        return new Builder()
                .id(KB_ID_PREFIX + kbId).kbId(kbId).autoEnabled(enabled)
                .updatedBy(operator).updatedAt(LocalDateTime.now())
                .build();
    }

    public ConflictSettings toggle(boolean enabled, String operator) {
        return new Builder()
                .id(this.id).kbId(this.kbId).autoEnabled(enabled)
                .updatedBy(operator).updatedAt(LocalDateTime.now())
                .build();
    }

    public String getId() { return id; }
    public String getKbId() { return kbId; }
    public boolean isAutoEnabled() { return autoEnabled; }
    public String getUpdatedBy() { return updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private String kbId;
        private boolean autoEnabled;
        private String updatedBy;
        private LocalDateTime updatedAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder kbId(String v) { this.kbId = v; return this; }
        public Builder autoEnabled(boolean v) { this.autoEnabled = v; return this; }
        public Builder updatedBy(String v) { this.updatedBy = v; return this; }
        public Builder updatedAt(LocalDateTime v) { this.updatedAt = v; return this; }

        public ConflictSettings build() {
            if (id == null) id = GLOBAL_ID;
            return new ConflictSettings(this);
        }
    }
}
