package com.github.shikejing.reportenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.engine.stream.ExternalSorter;
import com.github.shikejing.reportenginejava.engine.stream.Progress;
import com.github.shikejing.reportenginejava.engine.stream.RowCursor;
import com.github.shikejing.reportenginejava.engine.stream.RowInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部排序正确性测试：外部归并排序的输出必须与历史实现的「整体读入 + 稳定排序」
 * 逐条一致 —— 包括相等键保持输入顺序（稳定性）、null 永远末尾（不受升降序影响）、
 * 数值列按数值比较。
 */
class ExternalSortCorrectnessTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private RowInput writeInput(List<Map<String, Object>> rows) throws Exception {
        Path file = tempDir.resolve("in.jsonl");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map<String, Object> r : rows) {
                w.write(mapper.writeValueAsString(r));
                w.write('\n');
            }
        }
        return new com.github.shikejing.reportenginejava.engine.stream.JsonlRowInput(file, mapper);
    }

    private ExternalSorter.KeyExtractor numericKey(String field) {
        return (i, r) -> {
            Object v = r.get(field);
            return v == null ? null : ExternalSorter.Key.number(((Number) v).doubleValue());
        };
    }

    private List<Map<String, Object>> readSorted(Path run, ExternalSorter sorter) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (RowCursor c = sorter.openSorted(run)) {
            Object r;
            while ((r = c.next()) != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) r;
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, Object> row(Object v) {
        return row(v, null);
    }

    private Map<String, Object> row(Object v, Integer idx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", v);
        m.put("tag", "t");
        if (idx != null) {
            m.put("idx", idx);
        }
        return m;
    }

    @Test
    void matchesInMemoryStableSort_ascending_andNullsLast() throws Exception {
        // 故意让行数远大于 runSize，触发多段 + 多趟归并（FAN_IN=16 也覆盖到第二趟）。
        int n = 50_000;
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            // 大量重复键（0..99），制造稳定性决胜；每 ~2000 行插入一个 null
            Integer v = (i % 2000 == 1999) ? null : rnd.nextInt(100);
            rows.add(row(v, i));
        }

        RowInput input = writeInput(rows);
        ExternalSorter sorter = new ExternalSorter(mapper);
        Path run = sorter.sort(input, numericKey("k"), true, 333, tempDir, "t-", Progress.NOOP);
        List<Map<String, Object>> got = readSorted(run, sorter);

        // 参考实现：旧版 List.sort（稳定）+ null 末尾
        List<Map<String, Object>> expected = new ArrayList<>(rows);
        expected.sort(legacyComparator());

        assertThat(got).hasSameSizeAs(expected);
        Integer prevKey = null;
        Integer prevIdx = -1;
        boolean sawNulls = false;
        for (int i = 0; i < n; i++) {
            Object gk = got.get(i).get("k");
            Object ek = expected.get(i).get("k");
            assertThat(gk).isEqualTo(ek);
            int gIdx = (Integer) got.get(i).get("idx");
            int eIdx = (Integer) expected.get(i).get("idx");
            // 稳定排序：外部归并输出的「相等键内原始下标序列」必须与内存稳定排序完全一致。
            assertThat(gIdx).isEqualTo(eIdx);
            if (gk != null) {
                assertThat(sawNulls).isFalse();
                Integer key = (Integer) gk;
                if (prevKey != null && key.equals(prevKey)) {
                    assertThat(gIdx).isGreaterThan(prevIdx); // 相等键保持输入顺序
                }
                prevKey = key;
                prevIdx = gIdx;
            } else {
                sawNulls = true;
            }
        }
        assertThat(sawNulls).isTrue();
        Files.deleteIfExists(run);
    }

    @Test
    void descending_stillKeepsNullsLast_andMatchesLegacy() throws Exception {
        int n = 20_000;
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            Integer v = (i % 1500 == 0) ? null : rnd.nextInt(50);
            rows.add(row(v, i));
        }

        RowInput input = writeInput(rows);
        ExternalSorter sorter = new ExternalSorter(mapper);
        Path run = sorter.sort(input, numericKey("k"), false, 177, tempDir, "d-", Progress.NOOP);
        List<Map<String, Object>> got = readSorted(run, sorter);

        List<Map<String, Object>> expected = new ArrayList<>(rows);
        expected.sort(legacyComparatorDesc());

        for (int i = 0; i < n; i++) {
            assertThat(got.get(i).get("k")).isEqualTo(expected.get(i).get("k"));
            assertThat(got.get(i).get("idx")).isEqualTo(expected.get(i).get("idx"));
        }
        long nullCount = rows.stream().filter(r -> r.get("k") == null).count();
        for (int i = n - (int) nullCount; i < n; i++) {
            assertThat(got.get(i).get("k")).isNull();
        }
        Files.deleteIfExists(run);
    }

    /** 复刻旧 V1Renderer 的比较规则（ASC）：非空数值比较，null 末尾，稳定（List.sort 稳定）。 */
    private Comparator<Map<String, Object>> legacyComparator() {
        return (a, b) -> {
            Object x = a.get("k");
            Object y = b.get("k");
            if (x == null && y == null) {
                return 0;
            }
            if (x == null) {
                return 1;
            }
            if (y == null) {
                return -1;
            }
            return Double.compare(((Number) x).doubleValue(), ((Number) y).doubleValue());
        };
    }

    private Comparator<Map<String, Object>> legacyComparatorDesc() {
        Comparator<Map<String, Object>> base = legacyComparator();
        return (a, b) -> {
            Object x = a.get("k");
            Object y = b.get("k");
            if (x == null || y == null) {
                return base.compare(a, b); // null 规则不反转
            }
            return -base.compare(a, b);
        };
    }

    @Test
    void emptyInput_returnsNoRunAndNoChunks() throws Exception {
        RowInput input = writeInput(Collections.emptyList());
        ExternalSorter sorter = new ExternalSorter(mapper);
        Path run = sorter.sort(input, numericKey("k"), true, 100, tempDir, "e-", Progress.NOOP);
        assertThat(run).isNull();
    }

    @Test
    void singleRun_withoutMerge_isStillSorted() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 100; i >= 0; i--) {
            rows.add(row(i));
        }
        RowInput input = writeInput(rows);
        ExternalSorter sorter = new ExternalSorter(mapper);
        // runSize 大于行数 → 只有一个有序段，不发生归并
        Path run = sorter.sort(input, numericKey("k"), true, 10_000, tempDir, "s-", Progress.NOOP);
        List<Map<String, Object>> got = readSorted(run, sorter);
        assertThat(got).hasSize(101);
        assertThat(got.get(0).get("k")).isEqualTo(0);
        assertThat(got.get(100).get("k")).isEqualTo(100);
        Files.deleteIfExists(run);
    }
}
