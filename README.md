# report-engine-java：报表定义与渲染服务

声明式报表定义的提交、校验、版本化保存与确定性渲染服务。

- 使用方提交 JSON 声明式报表定义，服务端**完整校验**后按版本保存；
- 渲染时**精确定位到某一个已提交的定义版本**，同一份定义 + 同一份数据反复渲染结果逐字节一致；
- **全流式、内存有界**：请求体逐行落盘、分批渲染，带排序时使用外部归并排序；常驻内存只与单批行数相关，不随输入总行数增长；
- 输出**按批返回**，超限输入在解析中尽早拒绝，支持取消 / 超时（大输入下检查点即时生效），失败中间产物整体丢弃；
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

### 4.2 分批输出与内存有界（大数据量）

渲染流水线是**全流式**的，无论一次提交多少行，渲染期间常驻内存都只与「单批行数」（和归并路数）相关，**不随输入总行数或请求体体积增长**：

1. **请求体流式落盘**：提交渲染时服务端不再把整个 `data` 数组反序列化进内存，而是用流式 JSON 解析器逐行读取，每行立即写入作业目录下的 `input.jsonl`（JSON-lines：`<原始序号>\t<行 JSON>`），内存中只持有当前行。临时落盘文件先写在 `{data-dir}/tmp/`（与作业目录同一文件系统），作业目录创建后**原子 move** 进去。
2. **顺序扫描渲染（无排序）**：渲染器对 `input.jsonl` 做两遍顺序扫描 —— 第一遍逐行做单元格类型校验（与历史行为一致：全部行校验通过后才产出任何分块），第二遍按 `batchSize` 分批格式化；每批立即写入独立分块文件并释放该批内存。
3. **外部归并排序（带排序）**：定义带 `sort` 时不做全量内存排序，而是
   - 一遍扫描，按 `batchSize` 切分；每批在内存中排序（比较器与历史版本完全一致）后落盘为一个**归并段（run）**；
   - 再以最多 `external-sort-fan-in` 路做多趟 k 路归并，直到归并结果按 `batchSize` 逐批产出；
   - 归并堆以「排序键比较 + 原始输入序号决胜」组织，严格复刻稳定排序（同键保持输入顺序、`null` 永远末尾）；中间归并段被下一趟消费后立即删除。
   - 常驻内存约为 `batchSize` 行的排序缓冲 + `fanIn` 个归并游标的当前行；归并段数量再多也不会增加常驻内存，只增加磁盘与趟数。
4. **分块原子发布**：分块先写 `work/` 临时文件，再通过**原子 move** 发布到 `chunks/` 目录，读者永远不会看到半写文件；作业成功后清空 `work/`（`input.jsonl` 保留在作业目录，便于排查）。

> 结论：`-Xmx64m` 这样的受限堆也能稳定跑完 40 万行以上的渲染（带排序同样成立），输出顺序与内容和旧实现逐条一致。受限堆复现见 [第 9 节](#9-本地验证受限堆与大输入)。

### 4.3 超限输入尽早拒绝

- 服务端在**流式解析请求体的同时计数**，行数一旦超过 `report.engine.render.max-rows`，立即返回 `400`（错误字段 `data`，消息含 `maxRows`），无需等整个请求体上传 / 处理完；
- 拒绝发生在作业记录创建**之前**：不创建 `jobs/{jobId}`、不写作业状态、不留下临时输入文件（`{data-dir}/tmp/` 中的落盘文件立即删除）—— **被拒绝的请求不产生任何作业记录或中间结果**；
- 请求字段校验失败（`defName` / `batchSize` / `data` 类型等）、请求体不是合法 JSON，同样在任何落盘前返回 `400`。

### 4.4 取消与超时

- `POST /api/render-jobs/{jobId}/cancel`：置取消标记，渲染线程在**检查点**检查并立即停止；
- 检查点分布在：输入流式扫描（每若干行）、外部排序的每个归并段 / 每趟归并 / 归并出队（每若干行）、每个分块产出之前。因此即使输入几十万行，取消或超时也会很快生效，不必等全部输入处理完；
- 取消置 `CANCELLED`，超时置 `FAILED`（错误信息含 `timeout`），**已产出的分块整体丢弃**（`chunks/` 与 `work/` 全部清空），不吐出半份结果；
- 取消标记使用独立文件（`cancel.flag`），不会被作业状态保存覆盖；
- 取消**不影响后续渲染**（线程池独立，见测试 `CancellationTest`、`LargeInputCancellationTest`）；
- 超时通过 `timeoutMs`（请求级）或 `report.engine.render.default-timeout-ms`（全局）控制。

### 4.5 失败与重启恢复

- 渲染中途失败（数据类型错误、超时等）：作业置 `FAILED`，中间产物（含外部排序归并段）整体丢弃；
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

所有渲染相关配置都位于 `report.engine.render.*` 前缀下（嵌套绑定，改完重启即生效）。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `report.engine.data-dir` | `data` | 数据存储根目录（定义、作业、分块、临时输入） |
| `report.engine.render.core-pool-size` | `4` | 渲染线程池核心线程数 |
| `report.engine.render.max-pool-size` | `16` | 渲染线程池最大线程数 |
| `report.engine.render.queue-capacity` | `200` | 渲染任务队列容量（超出返回 503） |
| `report.engine.render.batch-size` | `500` | **默认每批渲染行数（输出分块大小）**；请求未传 `batchSize` 时使用，直接决定渲染常驻内存规模 |
| `report.engine.render.default-timeout-ms` | `300000` | 默认渲染超时（毫秒）；请求未传 `timeoutMs` 时使用 |
| `report.engine.render.max-rows` | `1000000` | 单次渲染最大行数；**流式解析时一旦超过立即 400 拒绝**，不创建作业 |
| `report.engine.render.external-sort-fan-in` | `64` | 带排序时外部归并排序的单趟最大归并路数（≥ 2）。常驻内存约为 `batchSize + fanIn` 行；调大则归并趟数更少、临时 IO 更少，调小则更省内存 |

配置如何生效：

- **优先级**：请求参数（`batchSize` / `timeoutMs`）> 服务端配置（`report.engine.render.*`）> 内置默认值；
- 所有项都支持 Spring Boot 标准覆盖方式：`application.properties`、`--report.engine.render.batch-size=3` 命令行参数、`REPORT_ENGINE_RENDER_BATCH_SIZE=3` 环境变量等；
- 验收示例：把默认每批行数配成 3（不改任何请求参数），渲染 10 行得到 4 块，前三块各 3 行、最后一块 1 行，逐块拉取完整还原（见测试 `RenderConfigTest`）。

---

## 8. 存储布局

```
{data-dir}/
  tmp/                  # 请求体流式落盘的临时输入（原子 move 进作业目录；拒绝时立即删除）
  definitions/{scope}/{name}/
    latest              # 最新版本号（原子切换）
    versions/1.json     # 不可变的历史版本文件
    versions/2.json
  jobs/{jobId}/
    job.json            # 作业状态（原子更新）
    input.jsonl         # 渲染输入行文件（每行：<原始序号>\t<行JSON>，流式落盘）
    cancel.flag         # 取消标记（存在即取消）
    chunks/000000.json  # 已发布分块（原子发布）
    work/               # 临时分块与外部排序归并段（run-*.jsonl），成功清空 / 失败取消时整体丢弃
```

所有状态变更均为「临时文件 + 原子 move」，并发读取永远不会看到半写状态。

---

## 9. 本地验证（受限堆与大输入）

### 9.1 受限堆复现内存有界

自动化方式（forked JVM，默认 `-Xmx64m`，渲染 40 万行、带排序且强制多趟归并）：

```bash
mvn -Dtest=ExternalSortMemoryTest test
# 把堆调得更小：
mvn -Dtest=ExternalSortMemoryTest -Dmemory.test.xmx=40m test
```

该测试在 forked 进程内额外持有一块 guard 字节数组以制造堆压力：旧的全量载入实现在该堆大小下必然 `OutOfMemoryError`（退出码非 0，测试失败），流式 + 外部归并实现退出码 0 且校验输出行数、全局有序、重复键稳定顺序、分块大小。

手动方式（起真实 HTTP 服务）：

```bash
mvn -DskipTests package
java -Xmx64m \
     -Dreport.engine.render.batch-size=1000 \
     -jar target/report-engine-java-1.0-SNAPSHOT.jar

# 另开终端：生成 40 万行输入（带排序定义），提交渲染，观察进程内存平稳、作业成功
# 可配合 jcmd <pid> GC.heap_info / jconsole 观察堆占用不随后续行数增长。
```

### 9.2 关键自动化测试索引

| 测试 | 覆盖点 |
|---|---|
| `ExternalSortMemoryTest` | 受限堆（40/64m）下 40 万行渲染：内存有界、排序有序与稳定、分块正确 |
| `ExternalSortCorrectnessTest` | 外部排序输出与内存稳定排序逐条一致（重复键稳定、`null` 末尾、ASC/DESC、JSON/CSV） |
| `RenderConfigTest` | 配置项真正生效；`batch-size=3` 渲染 10 行 → 3/3/3/1 四块并可还原 |
| `MaxRowsRejectionTest` | 超限输入解析中立即 400 拒绝；作业目录数不增加、无临时文件残留；恰为上限可成功 |
| `LargeInputCancellationTest` | 12 万行（含排序归并）下取消 / 超时快速生效、分块与归并段整体丢弃、不影响后续渲染 |

跑全部测试：

```bash
mvn test
```
