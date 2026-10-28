package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热门词领域实体单元测试（不依赖 Quarkus 容器）
 */
class HotWordEntityTest {

    private static final String CONTENT = "如何重置密码";
    private static final String KB_ID = "kb0123456789abcdef0123456789abcdef";
    private static final String OPERATOR = "admin";

    @Test
    void createWithStatusEnabledShouldSucceed() {
        HotWordEntity entity = HotWordEntity.create(CONTENT, KB_ID, 3, HotWordEntity.STATUS_ENABLED, OPERATOR);

        assertEquals(CONTENT, entity.getContent());
        assertEquals(KB_ID, entity.getKbId());
        assertEquals(3, entity.getSortOrder());
        assertEquals(HotWordEntity.STATUS_ENABLED, entity.getStatus());
        assertEquals(OPERATOR, entity.getCreatedBy());
        // ID 由 IdGenerator 生成：hw + 32hex
        assertTrue(entity.getId().value().startsWith("hw"));
        assertEquals(34, entity.getId().value().length());
        assertEquals(entity.getCreatedAt(), entity.getUpdatedAt());
    }

    @Test
    void createWithNullSortOrderShouldDefaultToZero() {
        HotWordEntity entity = HotWordEntity.create(CONTENT, KB_ID, null, HotWordEntity.STATUS_DISABLED, OPERATOR);
        assertEquals(0, entity.getSortOrder());
        assertEquals(HotWordEntity.STATUS_DISABLED, entity.getStatus());
    }

    @Test
    void createWithIllegalStatusShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(CONTENT, KB_ID, 0, (short) 2, OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(CONTENT, KB_ID, 0, (short) -1, OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(CONTENT, KB_ID, 0, null, OPERATOR));
    }

    @Test
    void createWithBlankContentShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(" ", KB_ID, 0, HotWordEntity.STATUS_ENABLED, OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(null, KB_ID, 0, HotWordEntity.STATUS_ENABLED, OPERATOR));
    }

    @Test
    void createWithTooLongContentShouldThrow() {
        String longContent = "词".repeat(HotWordEntity.CONTENT_MAX_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(longContent, KB_ID, 0, HotWordEntity.STATUS_ENABLED, OPERATOR));
    }

    @Test
    void createWithBlankKbIdShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> HotWordEntity.create(CONTENT, " ", 0, HotWordEntity.STATUS_ENABLED, OPERATOR));
    }

    @Test
    void enableAndDisableShouldSwitchStatus() {
        HotWordEntity entity = HotWordEntity.create(CONTENT, KB_ID, 1, HotWordEntity.STATUS_DISABLED, OPERATOR);

        HotWordEntity enabled = entity.enable();
        assertEquals(HotWordEntity.STATUS_ENABLED, enabled.getStatus());

        HotWordEntity disabled = enabled.disable();
        assertEquals(HotWordEntity.STATUS_DISABLED, disabled.getStatus());
    }

    @Test
    void withContentShouldReplaceContentAndRefreshUpdatedAt() throws InterruptedException {
        HotWordEntity entity = HotWordEntity.create(CONTENT, KB_ID, 1, HotWordEntity.STATUS_ENABLED, OPERATOR);
        Thread.sleep(2); // 保证 updatedAt 时间戳可区分

        HotWordEntity updated = entity.withContent("如何修改手机号");

        assertEquals("如何修改手机号", updated.getContent());
        assertEquals(CONTENT, entity.getContent()); // 原实体不变
        assertTrue(!updated.getUpdatedAt().isBefore(entity.getUpdatedAt()));
    }

    @Test
    void withContentShouldValidate() {
        HotWordEntity entity = HotWordEntity.create(CONTENT, KB_ID, 1, HotWordEntity.STATUS_ENABLED, OPERATOR);
        assertThrows(IllegalArgumentException.class, () -> entity.withContent(" "));
        assertThrows(IllegalArgumentException.class,
                () -> entity.withContent("词".repeat(HotWordEntity.CONTENT_MAX_LENGTH + 1)));
    }

    @Test
    void withSortOrderShouldReplaceSortOrder() {
        HotWordEntity entity = HotWordEntity.create(CONTENT, KB_ID, 5, HotWordEntity.STATUS_ENABLED, OPERATOR);

        HotWordEntity updated = entity.withSortOrder(9);

        assertEquals(9, updated.getSortOrder());
        assertEquals(5, entity.getSortOrder()); // 原实体不变
        // withSortOrder(null) 默认 0
        assertEquals(0, entity.withSortOrder(null).getSortOrder());
    }

    @Test
    void domainBehaviorsShouldReturnNewImmutableInstances() {
        HotWordEntity original = HotWordEntity.create(CONTENT, KB_ID, 2, HotWordEntity.STATUS_DISABLED, OPERATOR);

        HotWordEntity enabled = original.enable();
        HotWordEntity sorted = enabled.withSortOrder(7);
        HotWordEntity renamed = sorted.withContent("新内容");
        HotWordEntity disabled = renamed.disable();

        // 每次领域行为都返回新实例，原实例不变
        assertNotEquals(original, enabled);
        assertNotSame(original, enabled);
        assertNotSame(enabled, sorted);
        assertNotSame(sorted, renamed);
        assertNotSame(renamed, disabled);

        // 原实体各字段保持不变
        assertEquals(HotWordEntity.STATUS_DISABLED, original.getStatus());
        assertEquals(2, original.getSortOrder());
        assertEquals(CONTENT, original.getContent());

        // 链式变换最终状态
        assertEquals(HotWordEntity.STATUS_DISABLED, disabled.getStatus());
        assertEquals(7, disabled.getSortOrder());
        assertEquals("新内容", disabled.getContent());

        // 不变字段（id/kbId/createdBy/createdAt）在变换中保持
        assertEquals(original.getId(), disabled.getId());
        assertEquals(original.getKbId(), disabled.getKbId());
        assertEquals(original.getCreatedBy(), disabled.getCreatedBy());
        assertEquals(original.getCreatedAt(), disabled.getCreatedAt());
    }
}
