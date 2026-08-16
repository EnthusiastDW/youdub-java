package com.youdub.replica.service.adapter.tts;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VoxCpmCppTtsProvider} 的 cfg 句长自适应逻辑测试。
 */
class VoxCpmCppTtsProviderTest {

    @Test
    void adaptiveCfg_shortSentence_raisesCfg() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 20, 2.0);
        assertEquals(2.5, cfg, 1e-9);
    }

    @Test
    void adaptiveCfg_longSentence_lowersCfg() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 150, 2.0);
        assertEquals(1.5, cfg, 1e-9);
    }

    @Test
    void adaptiveCfg_midSentence_keepsConfiguredCfg() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 80, 2.0);
        assertEquals(2.0, cfg, 1e-9);
    }

    @Test
    void adaptiveCfg_shortSentence_cappedAt3() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 10, 2.9);
        assertEquals(3.0, cfg, 1e-9);
    }

    @Test
    void adaptiveCfg_longSentence_floorAt1() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 200, 1.2);
        assertEquals(1.0, cfg, 1e-9);
    }

    @Test
    void adaptiveCfg_boundary40_treatedAsShort() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 40, 2.0);
        assertEquals(2.5, cfg, 1e-9);
    }

    @Test
    void adaptiveCfg_boundary120_treatedAsLong() {
        Double cfg = ReflectionTestUtils.invokeMethod(
                new VoxCpmCppTtsProvider(null, null, null),
                "adaptiveCfg", 120, 2.0);
        assertEquals(1.5, cfg, 1e-9);
    }
}
