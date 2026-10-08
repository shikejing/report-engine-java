# report-engine-java：报表定义与渲染服务

声明式报表定义的提交、校验、版本化保存与确定性渲染服务。

- 使用方提交 JSON 声明式报表定义，服务端**完整校验**后按版本保存；
- 渲染时**精确定位到某一个已提交的定义版本**，同一份定义 + 同一份数据反复渲染结果逐字节一致；
- 输出**按批返回**，支持取消 / 超时，失败中间产物整体丢弃；
- 所有读写操作按 `X-Scope` 作用域鉴权，越权返回 404，不泄漏存在性。

---

## 1. 快速开始

```bash
mvn spring-boot:build-image   # 或 mvn package
java -jar target/report-engine-java-1.0-SNAPSHOT.jar
```

服务默认监听 `8080`，数据落在 `./data`（见[配置项](#7-配置项)）。

### 1.1 提交定义

```bash
curl -X POST http://localhost:8080/api/definitions \
  -H 'X-Scope: tenant-a' -H 'Content-Type: application/json' \
  -d '{
    "name": "sales",
    "description": "销售报表",
    "columns": [
      {"field": "id",     "header": "编号", "type": "STRING"},
      {"field": "amount", "header": "金额", "type": "NUMBER", "format": "#,##0.00"},
      {"field": "day",    "header": "日期", "type": "DATE",   "format": "yyyy-MM-dd"},
      {"field": "active", "header": "有效", "type": "BOOLEAN"}
    ],
    "sort": {"field": "day", "direction": "ASC"}
  }'
```

返回 `201`，含服务端元数据：

```json
{"scope":"tenant-a","name":"sales","version":1,"formatVersion":2,"createdAt":"...","content":{...}}
```

### 1.2 提交渲染

```bash
curl -X POST http://localhost:8080/api/render-jobs \
  -H 'X-Scope: tenant-a' -H 'Content-Type: application/json' \
  -d '{
    "defName": "sales",
    "defVersion": 1,
    "format": "JSON",
    "batchSize": 500,
    "data": [
      {"id":"a1","amount":12.5,"day":"2024-01-02","active":true},
      {"id":"a2","amount":100,"day":1704067200000,"active":false}
    ]
  }'
```

返回作业 `201`，轮询 `GET /api/render-jobs/{jobId}` 直到 `status=SUCCEEDED`。

### 1.3 按批读取结果

```bash
curl -H 'X-Scope: tenant-a' 'http://localhost:8080/api/render-jobs/{jobId}/result?chunk=0'
```

- `format=JSON`：返回行数组 `[{"id":"a1","amount":"12.50","day":"2024-01-02","active":"true"}, ...]`
- `format=CSV`：返回 `text/csv` 文本，**首块含表头**，后续块仅数据行。

`chunk` 从 `0` 开始，到 `chunkCount-1` 结束，逐块拉取即可完整还原。

---

## 2. 定义格式与版本兼容规则

### 2.1 定义模型

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | 是 | 报表名称，`[a-zA-Z0-9_-]{1,100}`，同一作用域内唯一 |
| `description` | 否 | 描述 |
| `columns` | 是 | 列数组，至少 1 列 |
| `columns[].field` | 是 | 数据行字段名，`[a-zA-Z0-9_]{1,100}`，列内不允许重复 |
| `columns[].type` | 是 | `STRING` / `NUMBER` / `DATE` / `BOOLEAN` |
| `columns[].header` | 否 | 列标题，缺省取 `field` |
| `columns[].format` | 否 | 格式化模式，**仅 `NUMBER` / `DATE` 允许**（非法组合会被拒绝） |
| `columns[].transform` | 否 | 字符串转换 `UPPER` / `LOWER`，**仅 `STRING` 且格式版本 ≥ 2 允许** |
| `sort` | 否 | 排序规则 |
| `sort.field` | 是* | 排序列，**必须引用某个已定义列** |
| `sort.direction` | 否 | `ASC` / `DESC`，缺省 `ASC` |

### 2.2 两种版本号

- **提交版本 `version`**：同一作用域下同一名称的定义，每提交一次递增 1（`1,2,3,...`）。历史版本**永远保留、不可变**，渲染时通过 `defVersion` 精确定位。
- **格式版本 `formatVersion`**：定义*语言*的版本，决定使用哪个校验器与渲染器。当前新提交一律为 `2`。

### 2.3 兼容规则（核心承诺）

1. **历史版本不可变**：版本文件只追加、不覆盖。定义更新只会产生新版本，旧版本文件原封不动。
2. **旧渲染语义永不改变**：每个格式版本绑定独立的校验器与渲染器（`RendererRegistry`）。新增能力（如 v2 的 `transform`）只注册新渲染器，**v1 定义永远走 v1 渲染器，代码行为冻结**。
3. **存量定义无需迁移**：升级前提交的定义持久化时记录了当时的 `formatVersion`，升级后仍按该版本渲染，不需要人工迁移或重放。
4. **渲染精确绑定版本**：作业记录 `defVersion` 与 `formatVersion`，读取方拿到的结果必然对应某个已提交版本，不存在跨版本拼接。
5. **建议**：渲染请求始终显式传 `defVersion`，避免定义更新后输出发生非预期变化；不传则取最新版本。

### 2.4 版本演进示例

```
提交 v1（columns: id, amount）  → 渲染器 V1，输出 A
提交 v2（columns: id, amount, note）→ 渲染器 V2，输出 B（含 note）
渲染 defVersion=1 → 永远得到 A（与提交当时逐字节一致）
渲染 defVersion=2 → 得到 B
```

---

## 3. 校验规则

所有校验在**写入前**完成，错误信息定位到具体字段路径，例如：

```json
{"error":"validation failed","errors":[
  {"field":"name","message":"name is required"},
  {"field":"columns[0].type","message":"type must be one of STRING, NUMBER, DATE, BOOLEAN"},
  {"field":"sort.field","message":"sort.field 'x' does not reference any column"},
  {"field":"columns[1].field","message":"duplicate column field 'id'"}
]}
```

覆盖的校验类别：

- **字段缺失**：`name` / `columns` / `columns[].field` / `columns[].type` 等；
- **类型不符**：字段类型不是字符串 / 数组 / 对象；
- **引用不存在对象**：`sort.field` 引用了未定义的列；
- **字段组合不合法**：`format` 用在 `STRING`/`BOOLEAN` 列、`transform` 用在非 `STRING` 列或格式版本不足、列字段重复、数字/日期格式模式非法；
- **未知字段**：顶层或列内出现未声明字段（如 v1 定义出现 `transform`）。

非法定义**不产生任何记录**：校验失败时不写入版本文件、不更新 `latest` 指针（见测试 `DefinitionValidationTest.invalidDefinition_leavesNoRecord`）。

渲染时还会校验**数据行**：单元格类型与列类型不符会导致作业失败，错误定位到 `rows[i].field`，例如 `rows[0].amount: expected NUMBER but got String`。

---

## 4. 渲染语义与确定性

### 4.1 确定性保证

- 列输出顺序严格按定义 `columns` 顺序（`LinkedHashMap`）；
- 数字格式化使用固定模式，日期格式化使用固定模式 + **UTC 时区**，不依赖默认区域设置；
- 排序为**稳定排序**，`null` 永远排在末尾；
- 渲染器无状态。同一份定义 + 同一份数据，无论执行多少次、是否并发、是否重启，输出**逐字节一致**（见测试 `DefinitionVersioningTest`、`ConcurrentRenderTest`）。

### 4.2 分批、流式与内存语义（大数据量）

渲染全程是**流式**的，无论一次提交多少行，服务端常驻内存都只与「单批行数」相关，不随输入总行数 / 输入体积增长：

- **请求不落全量内存**：提交渲染时，服务端直接消费原始请求字节流，边解析边把数据行写入作业暂存文件（JSON Lines，每行一个数据对象），任何时刻只持有「当前正在解析的一行」。请求被接受后，暂存文件原子认领为该作业的输入文件；
- **渲染按批读取**：作业运行时从输入文件逐行读取，单元格校验、格式化都按批进行，内存中至多持有一批行；每完成一批立即写入独立分块文件，不一次性加载全部结果；
- **排序也是有界内存**：定义带 `sort` 时使用**外部归并排序**——先按每批行数建立有序段（建段内存 = 一批），段数过多时按固定路数（16 路）做多趟归并，每路在内存中只持有当前队首行。无论输入多少行，排序期常驻内存为 `O(批大小 + 归并路数)`；排序结果（含稳定性、null 末尾、升降序）与整体读入排序逐条一致；
- 分块先写工作目录临时文件，再通过**原子 move** 发布到 `chunks/` 目录，读者永远不会看到半写文件；
- 中间排序段写在作业的 `work/` 目录，被下一趟归并消费后立即删除；输出按 `chunk` 拉取。

### 4.3 行数上限与尽早拒绝

- 单次渲染行数上限由 `report.engine.render.max-rows` 控制（默认 100 万）；
- 服务端在**读取输入的同时逐行计数**，行数一旦超过上限立即返回 `400`（`{"error":"validation failed","errors":[{"field":"data","message":"too many rows: ... maxRows ..."}]}`），**不需要、也不会**把整个输入处理一遍；
- 被拒绝的请求在任何作业目录创建之前即中止，**不产生作业记录、作业目录、分块或暂存文件**（`jobs/.staging/` 下不残留临时输入）；
- 行数恰好等于上限的请求正常接受。

### 4.4 取消与超时（大输入下也及时）

- `POST /api/render-jobs/{jobId}/cancel`：置取消标记，渲染线程在**固定行数粒度的检查点**上响应（默认每 256 行一次，外加每个分块边界）。检查点贯穿请求后的全部阶段：单元格校验、外部排序建段、多趟归并、分批输出——即使单批行数配得很大，取消也不用等一个分块跑完；
- 超时通过 `timeoutMs`（请求级）或 `report.engine.render.default-timeout-ms`（全局）控制，在同样的检查点上判定（`当前时间 - 开始时间 > timeoutMs`）；
- 取消 / 超时后作业置为 `CANCELLED` / `FAILED`，**已产出的分块与外部排序段整体丢弃**（`chunks/`、`work/` 清空），不吐出半份结果；
- 取消标记使用独立文件（`cancel.flag`），不会被作业状态保存覆盖；
- 取消**不影响后续渲染**（线程池独立，见测试 `CancellationTest`、`LargeInputAbortTest`）。

### 4.5 失败与重启恢复

- 渲染中途失败（数据类型错误、超时等）：作业置 `FAILED`，中间产物整体丢弃；
- **重启恢复**：应用启动时扫描全部作业，仍处于 `PENDING` / `RUNNING` 的作业一律置为 `FAILED`（原因：服务重启），并丢弃中间产物 —— 重启后不存在停在中间状态且无法解释的作业；已成功的作业及其结果保留，重启后仍可读取；
- 渲染是确定性的，客户端收到失败后用同一份定义版本与数据重新提交即可（见测试 `JobRecoveryTest`）。

---

## 5. 鉴权模型

- 所有 `/api/**` 请求必须携带 `X-Scope` 头（作用域即租户），格式 `[a-zA-Z0-9_-]{1,64}`；
- 定义与作业均按作用域隔离存储（`definitions/{scope}/...`、`jobs/{jobId}` 记录 scope）；
- 越权访问其他作用域的资源（读 / 改 / 删 / 渲染 / 读结果）**一律返回 404**，与「不存在」相同，不泄漏其他作用域是否存在该资源；
- 列表接口只返回本作用域的定义（见测试 `AuthorizationTest`）。

---

## 6. API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/definitions` | 提交定义（创建新版本） |
| GET | `/api/definitions` | 列出本作用域定义（最新版本摘要） |
| GET | `/api/definitions/{name}` | 读取最新版本 |
| GET | `/api/definitions/{name}/versions/{version}` | 读取指定历史版本 |
| DELETE | `/api/definitions/{name}` | 删除该定义的全部版本 |
| POST | `/api/render-jobs` | 提交渲染请求 |
| GET | `/api/render-jobs/{jobId}` | 查询作业状态 |
| POST | `/api/render-jobs/{jobId}/cancel` | 请求取消（幂等） |
| GET | `/api/render-jobs/{jobId}/result?chunk=n` | 按批读取结果 |

所有请求需携带 `X-Scope` 头。错误响应统一为 `{"error": "..."}` 或 `{"error":"validation failed","errors":[...]}`。

---

## 7. 配置项

配置通过 Spring Boot 标准方式提供：`application.properties`、环境变量
（如 `REPORT_ENGINE_RENDER_BATCH_SIZE`）、命令行参数
（如 `--report.engine.render.batch-size=3`）均可覆盖，服务启动时绑定一次。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `report.engine.data-dir` | `data` | 数据存储根目录（定义、作业、分块） |
| `report.engine.render.core-pool-size` | `4` | 渲染线程池核心线程数 |
| `report.engine.render.max-pool-size` | `16` | 渲染线程池最大线程数 |
| `report.engine.render.queue-capacity` | `200` | 渲染任务队列容量（超出返回 503） |
| `report.engine.render.batch-size` | `500` | **默认每批渲染行数（输出分块大小）**；请求未传 `batchSize` 时采用此值 |
| `report.engine.render.default-timeout-ms` | `300000` | 默认渲染超时（毫秒）；请求传 `timeoutMs` 时按请求值 |
| `report.engine.render.max-rows` | `1000000` | 单次渲染最大行数，**读取输入时超出即返回 400**，不创建作业 |

> 渲染相关配置统一挂在 `report.engine.render.*` 前缀下，绑定到
> `EngineProperties.Render`（`report.engine.data-dir` 为顶层项）。
> 修改这些配置后实际分块、超时、上限行为立即随之变化。

**验收示例（默认每批行数）**：把 `report.engine.render.batch-size` 配成 `3`，
请求里不传 `batchSize`，渲染 10 行应得到 4 块：前三块各 3 行、最后一块 1 行，
逐块（`chunk=0..3`）拉取可完整还原 10 行（见测试 `RenderConfigBindingTest`）。

```bash
java -jar target/report-engine-java-1.0-SNAPSHOT.jar --report.engine.render.batch-size=3
```

---

## 8. 存储布局

```
{data-dir}/
  definitions/{scope}/{name}/
    latest              # 最新版本号（原子切换）
    versions/1.json     # 不可变的历史版本文件
    versions/2.json
  jobs/
    .staging/           # 请求暂存区：数据流式落盘为 req-*.jsonl；
                        # 接受后原子移入作业目录，拒绝则立即删除（无残留）
    {jobId}/
      job.json            # 作业状态（原子更新）
      request-data.jsonl # 渲染输入数据（JSON Lines，每行一个数据对象，流式落盘）
      cancel.flag         # 取消标记（存在即取消）
      chunks/000000.json  # 已发布分块（原子发布）
      work/               # 临时分块与外部排序段（失败/取消时整体清空）
```

所有状态变更均为「临时文件 + 原子 move」，并发读取永远不会看到半写状态。
作业成功后 `work/` 下不残留排序段；取消 / 超时 / 失败后 `chunks/` 与
`work/` 都被整体清空。

---

## 9. 本地验证：受限堆下的大输入渲染

自动化测试 `BoundedMemoryRenderTest` 会 fork 一个 `-Xmx48m` 的 JVM，用真实
渲染器渲染 60 万行（含带排序、触发多趟外部归并的场景）。旧的「整体读入」
实现在该堆下必然 `OutOfMemoryError`；当前流式实现稳定成功且校验输出完整、有序。

在本地手动复现（先 `mvn test-compile`，再用测试 classpath 运行；
该入口位于 `src/test`，不打进发布 jar）：

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/re-cp.txt
CP="target/classes:target/test-classes:$(cat /tmp/re-cp.txt)"

# 48MB 堆，60 万行，每批 500 行，不带排序
java -Xmx48m -cp "$CP" \
  com.github.shikejing.reportenginejava.tests.membound.BoundedMemoryRenderMain \
  /tmp/re-render 600000 500 false

# 同样的堆与数据量，带排序（外部归并）
java -Xmx48m -cp "$CP" \
  com.github.shikejing.reportenginejava.tests.membound.BoundedMemoryRenderMain \
  /tmp/re-render-sorted 600000 500 true
```

成功输出形如 `RENDER_OK rows=600000 batchSize=500 sorted=true chunks=1200 ...`，
退出码为 0；把行数调大到超过 `maxRows`（默认 100 万）可通过 HTTP 接口观察
读取途中即返回的 400。运行整个测试集：

```bash
mvn test
```

相关测试一览：

| 测试 | 覆盖点 |
|---|---|
| `RenderConfigBindingTest` | 渲染配置项真正绑定；默认 batch-size=3 → 10 行 4 块（3/3/3/1） |
| `EarlyRejectionTest` | 超限输入读取途中 400，拒绝不留作业记录 / 暂存残留；恰好上限可成功 |
| `LargeInputAbortTest` | 30 万行（含外部排序阶段）取消 / 超时秒级生效，分块整体丢弃 |
| `BoundedMemoryRenderTest` | `-Xmx48m` 下 60 万行（排序 / 不排序）渲染成功、输出正确 |
| `ExternalSortCorrectnessTest` | 外部归并排序与历史内存稳定排序逐条一致（稳定性、null 末尾、升降序） |
| `SortedStreamingRenderTest` | 带排序定义端到端：跨块分块与参考稳定排序一致、DESC null 末尾 |
