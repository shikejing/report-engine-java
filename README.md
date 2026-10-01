# Report Engine

一个基于 Spring Boot 的声明式报表定义与渲染服务。服务先完整校验并按不可变版本保存定义，再基于提交当时的定义快照渲染输入数据。

## 1. 快速启动

```bash
mvn spring-boot:run
```

默认使用本地文件存储，数据目录为：

```text
target/report-engine-data/
├── definitions/<scope>/<reportKey>/v-<version>.json
└── jobs/<scope>/<jobId>/
    ├── definition.json
    ├── job.json
    ├── rows.ndjson   # 仅 SUCCEEDED 后存在
    └── rows.index    # 批次字节偏移索引，仅 SUCCEEDED 后存在
```

生产环境应把 `reportengine.data-directory` 指向持久化磁盘。所有元数据先写临时文件，再原子移动；渲染结果只有在整份输入处理成功后才发布。

## 2. 鉴权

当前版本通过请求头表示调用方身份：

| Header | 说明 |
|---|---|
| `X-Scope` | 调用方所属作用域，格式 `[a-z0-9_-]` |
| `X-Role` | `ADMIN` 或 `READER` |

- `ADMIN`：创建、更新、删除本作用域定义；也可读取和渲染。
- `READER`：读取定义和发起渲染。
- 作用域之间的目录和任务完全隔离。读取其他作用域不存在于调用方作用域的同名对象时返回 `404`，不暴露其他作用域是否存在同名定义。
- 缺少或伪造认证头返回 `401`；已认证但角色不足返回 `403`。

真实部署应在网关或认证过滤器中把可信身份转换为这些内部头，不能让外部请求直接伪造内部身份。

## 3. 定义 API

### 3.1 创建或更新

```http
PUT /api/reports/sales
X-Scope: tenant-a
X-Role: ADMIN
Content-Type: application/json
X-Engine-Version: 2
```

`X-Engine-Version` 是服务端保存的行为版本，不属于客户端可随意修改的定义体。首次创建可省略，默认最新版本 `2`；存量报表更新时省略则继承当前 `engineVersion`，因此无需人工迁移。需要启用新行为时显式升级，例如 `X-Engine-Version: 2`；不能降级。

定义体示例：

```json
{
  "reportKey": "sales",
  "name": "Sales Report",
  "fields": [
    {"name": "customer", "type": "STRING", "required": true},
    {"name": "amount", "type": "NUMBER", "required": true}
  ],
  "parameters": [
    {"name": "taxRate", "type": "NUMBER", "required": false, "defaultValue": 0.1}
  ],
  "columns": [
    {
      "name": "customerUpper",
      "label": "Customer",
      "dataType": "STRING",
      "expression": {
        "type": "FUNCTION",
        "op": "UPPER",
        "args": [{"type": "FIELD", "field": "customer"}]
      }
    },
    {
      "name": "tax",
      "label": "Tax",
      "dataType": "NUMBER",
      "expression": {
        "type": "FUNCTION",
        "op": "ROUND",
        "args": [
          {
            "type": "FUNCTION",
            "op": "MUL",
            "args": [
              {"type": "FIELD", "field": "amount"},
              {"type": "PARAMETER", "parameter": "taxRate"}
            ]
          },
          {"type": "CONSTANT", "value": 2}
        ]
      }
    }
  ]
}
```

### 3.2 校验规则

写入前会收集完整错误列表，任何非法字段都不会产生版本文件或半条记录。错误响应形如：

```json
{
  "code": "validation_failed",
  "issues": [
    {"field": "$.columns[0].expression.field", "message": "references undefined field 'x'"}
  ]
}
```

校验覆盖：

- 必填字段缺失、空白、命名格式错误。
- JSON 类型与 `STRING`、`NUMBER`、`BOOLEAN` 不匹配。
- 字段、参数、列名重复。
- 表达式引用未声明字段或参数。
- 列声明类型与表达式推断类型不一致。
- 函数不存在、参数个数错误、参数类型错误。
- 常量除数为零；`ROUND` 位数必须是 `0..18` 的整数常量。
- URL 中的 `reportKey` 与定义体不一致。

## 4. 表达式和版本兼容

每个保存的版本都是不可变快照，并包含：

- 报表定义版本：`version`，同一 `<scope>/<reportKey>` 内单调递增。
- 渲染行为版本：`engineVersion`，决定允许哪些表达式以及旧行为如何解释。

当前行为版本：

| Engine version | 能力 |
|---|---|
| `1` | `ADD`、`SUB`、`MUL`、`DIV`、`UPPER`、`CONCAT` |
| `2` | 在 v1 基础上增加 `LOWER`、`ROUND` |

兼容承诺：

1. 新增能力只能出现在更高的 `engineVersion`。
2. 已保存版本的 `engineVersion` 永不改变；即使服务升级，旧版本仍按 v1 语义渲染。
3. 普通定义更新若省略 `X-Engine-Version`，继续使用当前行为版本，不需要迁移。
4. 显式升级后保存为一个新版本，旧版本仍可通过 `?version=<n>` 读取和渲染。
5. 渲染任务保存 `definition.json` 快照以及 `definitionVersion`，因此后续定义更新不会影响旧任务，也不会产生跨版本拼接。

数值运算使用 `BigDecimal`、`HALF_UP` 和 ROOT Locale；相同定义版本、相同输入和相同参数在并发、重试和重启后的输出逐行一致。

## 5. 渲染 API

输入使用 NDJSON：每行一个 JSON 对象。服务逐行读取、逐行渲染，不会把全部输入或输出一次性读入内存。

```bash
curl -X POST 'http://localhost:8080/api/reports/sales/renders?version=1' \
  -H 'X-Scope: tenant-a' \
  -H 'X-Role: READER' \
  -H 'Content-Type: application/x-ndjson' \
  -H 'X-Parameters-JSON: {"taxRate":0.1}' \
  --data-binary $'{"customer":"acme","amount":100}\n{"customer":"beta","amount":200}\n'
```

成功响应：

```json
{
  "jobId": "...",
  "scope": "tenant-a",
  "reportKey": "sales",
  "definitionVersion": 1,
  "status": "SUCCEEDED",
  "totalRows": 2
}
```

状态：

- `PENDING` / `RUNNING`：尚未发布结果。
- `SUCCEEDED`：完整输出已经原子发布，可重复读取。
- `FAILED`：渲染失败，临时输出整体丢弃，可安全重试。
- `CANCELLED`：取消或超时，临时输出整体删除，不影响后续渲染。

### 5.1 按批读取

```http
GET /api/reports/renders/{jobId}/rows?offset=0&limit=100
```

服务端使用每行批次的字节偏移索引定位到请求所在批次，只跳过批内少量行，然后返回当前批次，避免读取大偏移时从头扫描完整文件；`limit` 最大不超过配置的批次大小。

### 5.2 取消和超时

```http
POST /api/reports/renders/{jobId}/cancel
```

取消和成功发布使用同一个任务锁比较状态并移动文件，因此只有一个结果会赢：要么完整结果成为 `SUCCEEDED`，要么没有结果文件并进入 `CANCELLED`，不会出现半份输出。

超时由 `reportengine.render-timeout` 控制。超时按取消处理，不返回部分结果。客户端断开也只影响当前 HTTP 调用，磁盘上的临时文件会随失败处理删除，后续渲染仍可继续。

## 6. 重启恢复

服务启动时扫描任务目录：

- `PENDING` 或 `RUNNING` 的任务被明确标记为 `FAILED`。
- `errorMessage` 写明由进程重启中断，可安全重试。
- `.tmp-rows-*` 临时文件被删除。
- `SUCCEEDED`、`FAILED`、`CANCELLED` 的终态保持可解释且幂等。

定义保存使用临时文件和原子移动，启动时也会清理残留 `.tmp-*` 文件。

## 7. 配置项

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `server.port` | `0`（开发/测试随机端口） | HTTP 端口，生产建议显式配置 |
| `reportengine.data-directory` | `target/report-engine-data` | 定义和渲染结果根目录 |
| `reportengine.render-timeout` | `2m` | 单个渲染的最长执行时间 |
| `reportengine.batch-size` | `100` | 输出读取批次上限，也是刷盘检查行数 |
| `reportengine.worker-threads` | `2` | 同时进行的流式渲染许可数 |

示例：

```properties
server.port=8080
reportengine.data-directory=/var/lib/report-engine
reportengine.render-timeout=5m
reportengine.batch-size=500
reportengine.worker-threads=8
```

## 8. 其他端点

```text
GET    /api/reports/{reportKey}?version=<n>   # n 省略时读取最新版本
GET    /api/reports/{reportKey}/versions      # 列出不可变历史版本
DELETE /api/reports/{reportKey}               # ADMIN 删除当前作用域下的定义
GET    /api/reports/renders/{jobId}           # 查询渲染状态
POST   /api/reports/renders/{jobId}/cancel    # 取消活动渲染
```

## 9. 测试

```bash
mvn test
```

测试覆盖：

- 字段缺失、类型不符、未知对象引用、非法函数组合。
- 非法定义不留下版本文件。
- v1/v2 能力隔离和显式升级。
- 相同输入的并发渲染逐行一致。
- 定义更新与读取并发时版本不串接。
- 跨作用域定义和渲染任务不可见。
- 渲染失败、取消、超时和重启恢复。

## 10. 当前边界

本实现优先保证单节点、本地持久化卷上的正确性和流式处理。横向扩展到多实例时，应把文件目录替换为支持原子提交和租约的对象/数据库存储，并把 `X-Scope`/`X-Role` 头接入统一认证系统。
