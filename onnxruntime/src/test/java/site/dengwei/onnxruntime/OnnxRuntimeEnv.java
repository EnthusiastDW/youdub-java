package site.dengwei.onnxruntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ONNX Runtime 本机环境检查。
 * <p>用于集成测试跳过条件判断。</p>
 */
public final class OnnxRuntimeEnv {

    private static final Logger log = LoggerFactory.getLogger(OnnxRuntimeEnv.class);
    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean ok = false;
        try {
            ai.onnxruntime.OrtEnvironment.getEnvironment();
            ok = true;
        } catch (Throwable t) {
            log.warn("ONNX Runtime 本机库不可用: {} — 集成测试将被跳过。", t.getMessage());
            log.warn("Windows 系统需要安装 Microsoft Visual C++ 2015-2022 Redistributable (x64)。");
        }
        NATIVE_AVAILABLE = ok;
    }

    /** ONNX Runtime 本机库是否可加载。 */
    public static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    private OnnxRuntimeEnv() {}
}
