# MikuTag

> 作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发

MikuTag 是一款基于 Paper Dialog 菜单的称号插件，兼容 Paper 与 Folia。玩家通过图形化菜单浏览商城、购买与佩戴称号，管理员可以用菜单完整地创建和管理称号；支持金币 / 权限获取条件、有效期、PlaceholderAPI 变量，以及从 PlayerTitle 一键迁移数据。

## 功能特性

- 原生 Dialog 菜单（商城 / 称号详情 / 我的称号 / 管理 / 删除确认），无需任何菜单前置
- 称号支持图标、MiniMessage 或 & 颜色码的名称与描述、有效期（0 = 永久）、排序
- 获取条件可叠加：免费 / Vault 金币 / 权限节点（须全部满足）
- 商城出售开关：活动限定称号可正常发放，但不出现在商城
- 管理菜单图形化新建 / 编辑 / 删除称号，保存即生效并自动注册权限节点
- 玩家仓库一键佩戴 / 卸下；已过期称号在仓库中可见但不能佩戴
- 发放 / 收回指令支持指定天数；`behavior.auto-equip` 可在玩家获得新称号时自动佩戴
- 显示前后缀装饰（MiniMessage 或 & 颜色码），一处配置即可全局调整称号展示样式
- 存储支持 SQLite / MySQL / MariaDB，数据异步批量写回，不阻塞游戏线程
- 多服共享 MySQL / MariaDB 时定时重读称号定义，一处修改全服生效
- 支持从 PlayerTitle 迁移称号定义与玩家持有记录（幂等，可重复执行）
- Paper / Folia 双端兼容

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 或 Folia（api-version 26.2 版本线） |
| Java | 25 |
| 可选前置 | Vault（金币条件与金币购买）、PlaceholderAPI（变量） |

> SQLite / MySQL / MariaDB 的 JDBC 驱动由插件在开服时自动下载并按需缓存，无需手动安装。

## 安装

1. 将 `MikuTag-<版本>.jar` 放入服务端 `plugins/` 目录。
2. 启动一次服务端，插件会生成配置目录 `plugins/MikuTag/`：
   - `config.yml`：存储、菜单与显示参数
   - `messages.yml`：全部提示与菜单文案
3. 首次启动需要在能访问 Maven 仓库的网络环境下完成 JDBC 驱动下载，之后直接复用缓存，不再重复下载。
4. 按需修改配置后，执行 `/mikutag reload` 或重启服务端生效。

## 配置说明

### config.yml

| 键 | 说明 | 默认 |
| --- | --- | --- |
| `storage.type` | 存储类型：`sqlite` / `mysql` / `mariadb`（切换类型需重启服务端） | `sqlite` |
| `storage.flush-interval-seconds` | 数据异步批量写回的间隔（秒） | 30 |
| `storage.mysql.*` / `storage.mariadb.*` | 远端库连接参数（host / port / database / user / password / parameters） | — |
| `menu.page-size` | 商城 / 仓库 / 管理菜单每页显示的称号数量（1-20） | 8 |
| `menu.click-throttle-ms` | 同一玩家两次菜单点击的最小间隔（毫秒），用于防连点；0 表示不限制 | 200 |
| `sync.title-interval-seconds` | 称号定义定时重读间隔（秒），仅 mysql / mariadb 生效 | 10 |
| `placeholder.empty` | 玩家未佩戴称号时 `%mikutag_title%` 的返回内容 | 空 |
| `display.prefix` / `display.suffix` | 展示称号时拼接的前后缀装饰（MiniMessage 或 & 颜色码），不会写入称号定义 | 空 |
| `behavior.auto-equip` | 玩家获得新称号且当前未佩戴时自动佩戴 | false |

### messages.yml

全部提示与菜单文案，支持 MiniMessage 与 `<prefix>` 前缀标签，可整体自定义。

### 颜色代码

称号名称 / 描述、`display` 前后缀，以及 `messages.yml` 里的全部文案，都同时支持两种写法，且可以混用：

- **MiniMessage**：`<red>`、`<bold>`、`<gradient:#7fd7ff:#b48cff>…</gradient>` 等，适合渐变、十六进制等高级样式；
- **旧式颜色码**：`&c`、`&l`、`&r`、`&#ff0000` 等，与常见老插件 / 配置文件写法一致。

旧式颜色码按游戏内规则转换：颜色码会同时重置此前的加粗 / 斜体等格式，`&r` 等同于恢复默认样式。
正文里需要显示字面 `&` 时写成 `&&`（例如 `M&&M` 显示为 `M&M`）。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mikutag` | 打开我的称号（仓库） | `mikutag.use` |
| `/mikutag shop` | 打开称号商城 | `mikutag.use` |
| `/mikutag equip <称号ID>` | 佩戴称号（支持 TAB 补全） | `mikutag.use` |
| `/mikutag unequip` | 卸下当前称号 | `mikutag.use` |
| `/mikutag open <玩家>` | 为指定玩家打开仓库菜单 | `mikutag.admin` |
| `/mikutag admin` | 打开称号管理菜单（新建 / 编辑 / 删除） | `mikutag.admin` |
| `/mikutag give <玩家> <称号ID> [天数]` | 发放称号（天数省略或 0 = 永久） | `mikutag.admin` |
| `/mikutag take <玩家> <称号ID>` | 收回称号 | `mikutag.admin` |
| `/mikutag create <ID> <条件> <名称> [天数]` | 命令行新建称号（用法见下） | `mikutag.admin` |
| `/mikutag import` | 从 PlayerTitle 迁移数据 | `mikutag.admin` |
| `/mikutag reload` | 重载配置并重读称号定义 | `mikutag.admin` |
| `/mikutag info` | 查看运行状态（版本 / 存储 / 称号数 / Vault / 占位符） | `mikutag.admin` |

别名：`/mt`、`/mtag`、`/title`、`/titles`

### 称号 ID 与获取条件

- 称号 ID 仅允许字母、数字、下划线、短横线，长度 1-64 位。
- 获取条件串用 `|` 分隔多项，购买时须全部满足；`free` 或留空表示免费领取：
  - `VAULT:1000`：需要 1000 金币（依赖 Vault）
  - `PERMISSION:mikutag.vip`：需要对应权限节点
- 名称与描述支持 MiniMessage 标签与 `&` 颜色码；`<click>` / `<hover>` 等事件标签会被自动剔除。

`/mikutag create` 示例（含空格的参数需用引号包裹）：

```
/mikutag create vip "VAULT:1000 | PERMISSION:mikutag.vip" "<gradient:#7fd7ff:#b48cff>VIP 贵族</gradient>" 30
```

新建后可用 `/mikutag admin` 打开表单，继续调整图标、描述、是否出售等属性。

## 权限节点

| 节点 | 说明 | 默认 |
| --- | --- | --- |
| `mikutag.use` | 打开菜单、佩戴 / 卸下称号 | 所有玩家 |
| `mikutag.admin` | 管理权限（发放 / 收回 / 管理菜单 / 重载等） | OP |
| `mikutag.title.*` | 满足全部"权限型"称号的获取条件 | OP |
| 称号条件中的权限节点 | 按称号的 `PERMISSION` 条件自动注册，默认仅 OP 持有 | OP |

## PlaceholderAPI 变量

| 变量 | 说明 |
| --- | --- |
| `%mikutag_title%` | 当前佩戴称号（含颜色码与 `display` 前后缀），多数聊天 / TAB 插件可直接使用 |
| `%mikutag_title_strip%` | 当前佩戴称号的纯文本（去颜色，含前后缀） |
| `%mikutag_title_raw%` | 当前佩戴称号规范化后的 MiniMessage 串（`&` 颜色码已转换，不含前后缀，交由消费插件自行解析） |
| `%mikutag_title_id%` | 当前佩戴称号 ID |
| `%mikutag_count%` | 当前有效拥有的称号数量 |
| `%mikutag_has_<称号ID>%` | 是否持有某称号（true / false） |

> 变量数据取自内存缓存；未佩戴称号时的返回值由 `placeholder.empty` 控制。

## 数据迁移（PlayerTitle）

`/mikutag import` 可从 PlayerTitle 迁移称号定义与玩家持有 / 佩戴记录：

- 支持 PlayerTitle 的 SQLite 与 MySQL 存储，自动读取其 `plugins/PlayerTitle/storage.yml`。
- 称号 ID 映射为 `pt_<原ID>`，导入可重复执行且幂等，不会覆盖已有数据。
- 在线玩家的持有记录会被跳过（其数据在内存缓存中），待其离线后重新执行一次导入即可补齐。
- 若数据源为 SQLite 而 MikuTag 当前使用 MySQL / MariaDB（classpath 中缺少对应驱动），插件会明确提示：临时把 `storage.type` 改为 `sqlite` 并重启后再导入，导入完成后可改回。

## 存储与多服

- `sqlite`（默认）：数据文件为 `plugins/MikuTag/data.db`，适合单服使用。
- `mysql` / `mariadb`：多服可共享同一数据库，库即唯一数据源；称号定义按 `sync.title-interval-seconds` 定时重读，在任意一服新建 / 修改 / 删除称号，其他服会在一个周期内自动生效。
- 玩家数据修改即时入队，按 `storage.flush-interval-seconds` 批量写回；服务器关闭时自动落盘。