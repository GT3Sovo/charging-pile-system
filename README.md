# 智能充电桩调度计费系统

> 北京邮电大学 · 软件工程课程期末项目 · G12 组
>
> 基于 Spring Boot 的多充电桩智能调度与峰平谷分时段计费系统，支持虚拟时钟仿真与 Web 双端交互。

## 项目概览

本系统模拟一个充电站运营场景：5 个充电桩（3 快充 + 2 慢充）、等候区容量管理、峰平谷分时段电费计费、故障处理与优先重调度。系统通过虚拟时钟引擎秒级推进时间线，支持 32 条标准验收事件的全自动化测试。

**核心数据**
- 后端代码：~4,200 行 Java（32 个源文件）
- 前端：纯 HTML/CSS/JS 双端 SPA（管理员 + 客户终端）
- REST API：25 个端点
- 测试用例：23 个 JUnit 5 测试，仿真结果与手算零误差
- 文档：20 张 PlantUML 时序图 + 概要设计说明书 + 详细需求文档

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.2.5 | |
| JDK | 17 | |
| ORM | Spring Data JPA / Hibernate | 7 张数据表，11 个 JPA 实体 |
| 数据库 | H2（开发内存库）/ MySQL（生产） | dev/prod 双环境配置 |
| 密码加密 | Spring Security Crypto BCrypt | |
| Excel 读写 | Apache POI 5.2.5 | 验收用例导入导出 |
| 测试 | JUnit 5 | 23 个测试用例，100% 通过 |
| 构建 | Maven 3.9+ | |
| 文档 | PlantUML | 20 张时序图（.puml + .png） |
| 前端 | HTML5 + CSS3 + vanilla JavaScript | 苹果简约风 SPA，打包在 static 目录 |

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    前端 (static/)                        │
│         admin.html (管理端)  +  client.html (客户终端)     │
└──────────────┬──────────────────┬───────────────────────┘
               │  REST API         │
┌──────────────▼──────────────────▼───────────────────────┐
│              Controller 层 (25 个端点)                    │
│     ApiController (业务调度) + AccountController (账号)    │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│                   Service 层 (5 个)                       │
│  SchedulingEngine │ BillingEngine │ SimulationService    │
│  ReportService    │ TestSimulator                        │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│              Repository 层 (7 个 JPA 接口)                │
│        User │ Vehicle │ ChargingPile │ Bill              │
│        DetailList │ QueueRecord │ RechargeRecord         │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│                H2 (dev) / MySQL (prod)                   │
└─────────────────────────────────────────────────────────┘
```

**部署特点**：前后端内聚为单个 Spring Boot Jar 包，`java -jar` 一行命令启动，无需单独部署前端服务器。

## 核心模块

### 调度引擎 `SchedulingEngine`（1,083 行）

- **容量配置**：等候区 N=10，每桩排队队列 M=3（1 个在充 + 2 个等待）
- **S 策略贪心调度**：按排队号从等候区叫号，分配到总等待时间最短的充电桩
- **批量最优调度**：同类型空位 ≥2 且等候车辆 ≥2 时，枚举全排列选总完成时间最短方案
- **故障策略 A**：充电桩故障 → 立即结算在充车辆 → 驱逐排队车辆到 FAULT_QUEUE → 冻结等候区叫号 → 优先从故障队列重调度 → 清空后解冻
- **故障恢复**：收集同类型故障队列车辆 + 各桩排队车辆，按排队号合并重调度

### 计费引擎 `BillingEngine`（256 行）

**电价时段**
| 时段 | 时间范围 | 电价（元/度） |
|------|---------|-------------|
| 峰时 | 10:00-15:00, 18:00-21:00 | 1.0 |
| 平时 | 07:00-10:00, 15:00-18:00, 21:00-23:00 | 0.7 |
| 谷时 | 23:00-07:00 | 0.4 |

- 服务费固定 0.8 元/度
- **秒级精度跨时段计费**：将充电总时长按秒拆解到各电价时段，各时段电量按比例计算电费
- 总费用 = 电费 + 服务费，四舍五入精度控制

### 虚拟时钟仿真 `SimulationService`（434 行）

- 仿真范围：06:00:00 → 11:00:00（18,000 秒）
- 支持模式：秒级推进（tick）、跳至下个事件（next-event）、内置事件自动执行
- 32 条内置验收事件（四元组：事件类型/车辆ID/充电模式/数值），覆盖完整生命周期
- 故障自动恢复检测：每 tick 检测恢复时间

### 测试仿真器 `TestSimulator`（528 行）

- 独立运行仿真，全自动时间线推演
- 支持从 Excel 导入事件或使用内置事件
- 全程控制台打印生命周期日志
- 仿真结果与手算/Excel 零误差

## 数据库设计

| 表 | 核心字段 | 说明 |
|----|---------|------|
| `users` | id, username, password(BCrypt), role(ADMIN/MANAGER/CUSTOMER) | 三级角色 |
| `vehicles` | id(V1-V22), user_id, vehicle_type, battery_capacity, state, pile_id | 状态机驱动 |
| `charging_piles` | id(快充1-3/慢充1-2), type(FAST/TRICKLE), power, state(RUNNING/FAULT/OFFLINE) | 三种状态 |
| `queue_records` | vehicle_id, pile_id, queue_type(WAITING/PILE/FAULT), queue_num | 三级队列 |
| `recharge_records` | vehicle_id, pile_id, start/end_time, charged_amount, costs | 充电记录 |
| `bills` | bill_no, vehicle_id, charged_amount, charge_cost, service_cost, total_cost | 账单 |
| `detail_lists` | detail_no, pile_id, vehicle_id, bill_no, charged_amount, charge_duration | 详单 |

## 快速启动

### 环境要求
- JDK 17+
- Maven 3.9+

### 启动（开发模式，H2 内存数据库）

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 访问
| 页面 | 地址 | 说明 |
|------|------|------|
| 管理端 | http://localhost:8080/admin.html | 监控面板、调度操作、报表导出 |
| 客户端 | http://localhost:8080/client.html | 车主注册/登录、充电申请 |

### 内置账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | 管理员 |
| manager | manager123 | 经理 |
| system_driver | driver123 | 仿真车主 |

### 运行测试

```bash
cd backend
mvn test
```

## API 概览

### 业务调度 API（15 个端点）

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/state` | GET | 获取系统完整状态快照 |
| `/api/sim/reset` | POST | 重置仿真系统 |
| `/api/sim/mode` | POST | 切换仿真模式（builtin/free） |
| `/api/sim/tick` | POST | 仿真时钟前进 N 秒 |
| `/api/sim/next-event` | POST | 跳至下个事件 |
| `/api/charge/request` | POST | 提交充电申请 |
| `/api/charge/cancel` | POST | 取消/退单 |
| `/api/charge/change` | POST | 变更充电请求 |
| `/api/pile/breakdown` | POST | 模拟充电桩故障 |
| `/api/pile/recover` | POST | 恢复充电桩 |
| `/api/pile/stop` | POST | 关闭充电桩 |
| `/api/pile/start` | POST | 启动充电桩 |
| `/api/reports/daily` | GET | 日报聚合 |
| `/api/reports/summary` | GET | 日/周/月报表 |
| `/api/exports/csv` | GET | 导出详单/账单 CSV |

### 账号 API（10 个端点）

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 登录 |
| `/api/auth/logout` | POST | 登出 |
| `/api/auth/me` | GET | 当前用户信息 |
| `/api/account/vehicles` | GET/POST | 车辆绑定与管理 |
| `/api/admin/accounts` | GET | 管理员查看所有用户 |
| `/api/admin/users/{id}/role` | PUT | 修改用户角色 |

## 项目结构

```
ChargingPileDispatchBillingSystem/
├── backend/                        # Spring Boot 后端
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/chargingpile/
│       │   ├── DispatchBillingSystemApplication.java
│       │   ├── controller/
│       │   │   ├── ApiController.java          # 业务调度 (15 端点)
│       │   │   └── AccountController.java      # 账号管理 (10 端点)
│       │   ├── service/
│       │   │   ├── SchedulingEngine.java       # 调度引擎 (1,083 行)
│       │   │   ├── BillingEngine.java          # 计费引擎 (256 行)
│       │   │   ├── SimulationService.java      # 虚拟时钟 (434 行)
│       │   │   ├── ReportService.java          # 运营报表
│       │   │   └── TestSimulator.java          # 测试仿真器 (528 行)
│       │   ├── entity/ (11 JPA 实体 + 4 枚举)
│       │   ├── repository/ (7 Repository)
│       │   └── dto/
│       ├── main/resources/
│       │   ├── application.yml                 # dev/prod 双环境
│       │   └── static/                         # 前端 SPA
│       │       ├── index.html                  # 管理端
│       │       ├── client.html                 # 客户终端
│       │       ├── css/common.css
│       │       └── js/app-shared.js
│       └── test/java/com/chargingpile/ (5 测试类)
├── diagrams/                      # 20 张 PlantUML 时序图
├── 提交资料/                       # 验收用例 Excel + CSV
└── 设计文档/                       # 概要设计说明书 + 详细需求 + 教师验收参考答案
```

## 设计与验收文档

- `G12_充电桩调度计费系统_概要设计说明书.docx`
- `智能充电桩调度计费系统详细需求 20260327.docx`
- `智能充电桩调度计费系统详细需求 参考答案 20260508.pdf`
- `作业验收用例_G12.xlsx` — 32 条验收事件
- `第2次作业 G12组 修改版.docx` — 动态结构设计补充
