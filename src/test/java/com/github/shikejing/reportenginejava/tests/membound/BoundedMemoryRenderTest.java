package com.github.shikejing.reportenginejava.tests.membound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大输入内存有界测试：在一个 {@code -Xmx} 很小的独立 JVM 中，通过真实渲染器
 * 渲染几十万行（含带排序、触发多趟外部归并的场景）。
 *
 * <p>如果渲染过程把输入整体驻留内存（旧实现），该进程必然
 * {@code OutOfMemoryError} 退出；有界流式实现下应稳定成功且输出正确。
 *
 * <p>这同时就是 README「本地验证」描述的受限堆复现方式（见类中的
 * {@code java ... -Xmx48m ... BoundedMemoryRenderMain} 命令）。
 */
class BoundedMemoryRenderTest {

    @TempDir
    Path tempDir;

    /** 受限堆大小。远小于 60 万行输入的驻留体积，却远大于「单批 + 归并队首」所需。 */
    private static final String SMALL_HEAP = "48m";

    /** 60 万行（含 ~10 字符 id + 数字），落盘约 20MB+；旧实现整体读入必 OOM。 */
    private static final int ROWS = 600_000;
    private static final int BATCH = 500;

    @Test
    void largeUnsortedRender_completesUnderTinyHeap() throws Exception {
        Result r = runForked(ROWS, BATCH, false);
        assertThat(r.exitCode)
                .as("unsorted render must succeed under -Xmx" + SMALL_HEAP + "; stderr=\n" + r.stderr)
                .isZero();
        assertThat(r.stdout).contains("RENDER_OK");
    }

    @Test
    void largeSortedRender_completesUnderTinyHeap_andOutputIsSorted() throws Exception {
        Result r = runForked(ROWS, BATCH, true);
        assertThat(r.exitCode)
                .as("sorted render (external merge) must succeed under -Xmx" + SMALL_HEAP
                        + "; stderr=\n" + r.stderr)
                .isZero();
        assertThat(r.stdout).contains("RENDER_OK").contains("sorted=true");
    }

    private Result runForked(int rows, int batch, boolean sorted) throws Exception {
        Path work = Files.createTempDirectory(tempDir, "fork-");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xmx" + SMALL_HEAP);
        cmd.add("-Xss512k");
        // 让 OOM 立即失败而不是长时间 GC 抖动，便于稳定复现
        cmd.add("-XX:+HeapDumpOnOutOfMemoryError");
        cmd.add("-XX:HeapDumpPath=" + work.toString());
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add(BoundedMemoryRenderMain.class.getName());
        cmd.add(work.toString());
        cmd.add(Integer.toString(rows));
        cmd.add(Integer.toString(batch));
        cmd.add(Boolean.toString(sorted));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 子进程默认时区可能不是 UTC，渲染确定性不依赖这里，但统一掉更稳妥。
        pb.environment().put("TZ", TimeZone.getDefault().getID());
        Process process = pb.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread t1 = new Thread(() -> drain(process.getInputStream(), out));
        Thread t2 = new Thread(() -> drain(process.getErrorStream(), err));
        t1.start();
        t2.start();

        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("forked render did not finish in time");
        }
        t1.join();
        t2.join();
        return new Result(process.exitValue(),
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static void drain(java.io.InputStream in, ByteArrayOutputStream sink) {
        try (in) {
            in.transferTo(sink);
        } catch (Exception ignored) {
            // 进程异常退出时读流可能失败，忽略并保留已收集内容
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
