package com.ae2vm.trace;

import com.ae2vm.config.AE2VMConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2: server-side literal localization — en_us fallback chain + {n} substitution. */
class TraceLangTest {

    @Test
    void englishFallbackCoversUnknownKeys() {
        assertEquals("aevm.trace.nonexistent.key", TraceLang.format("aevm.trace.nonexistent.key"));
    }

    @Test
    void positionalSubstitutionWorks() {
        String s = TraceLang.format("aevm.trace.list.header", 5, 1, 1);
        assertTrue(s.contains("5") && s.contains("1"), "args must substitute: " + s);
    }

    @Test
    void languageSwitchRerendersInChineseAndBack() {
        String before = TraceLang.format("aevm.trace.record.next");
        try {
            AE2VMConfig.language = "zh_cn";
            TraceLang.reload();
            String zh = TraceLang.format("aevm.trace.record.next");
            assertTrue(zh.contains("下一笔") || zh.contains("订单"), "zh_cn text expected: " + zh);

            AE2VMConfig.language = "en_us";
            TraceLang.reload();
            assertEquals(before, TraceLang.format("aevm.trace.record.next"));
        } finally {
            AE2VMConfig.language = "en_us";
            TraceLang.reload();
        }
    }
}
