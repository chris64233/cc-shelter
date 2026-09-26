# cc-shelter

管理家庭成员、床位资源和入住安排。

## 主要业务规则

- **整体入住**：家庭全部成员必须安排到同一安置点的同一房间，不允许拆分家庭或部分占床；
  房间剩余床位必须不小于家庭人数，且任一成员需要无障碍设施时房间必须支持无障碍。
- **稳定选房**：在满足条件的房间中，按“入住后剩余床位最少、房间编号最小”的规则稳定选择。
- **唯一有效入住**：任一家庭成员已处于有效入住中时，整笔入住拒绝（`stays.active_household_id`
  唯一约束兜底，保证同一家庭至多一条有效入住）。
- **幂等入住/转移**：请求携带幂等键；相同内容重放返回首次结果，相同键不同内容返回 409 冲突；
  并发同键请求由唯一约束兜底并回放首次结果。
- **并发安全**：入住与转移在同一事务内对家庭行和目标安置点房间加悲观写锁（房间按 id 升序加锁避免死锁），
  并发入住不会超卖房间。
- **整体转移**：转移在同一事务内先锁定并占用目标安置点的合适房间，再释放原房间并结束原入住；
  目标无合适房间时整体回滚，原入住完全保留；重复转移、并发转移不会产生重复释放或双重入住。
- **事件留痕**：每次入住/转移写入不可变的 `stay_events` 记录（CHECK_IN / TRANSFER），只增不改。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/shelters` | 创建安置点 |
| POST | `/api/shelters/{id}/rooms` | 添加房间（床位数、无障碍标记） |
| GET | `/api/shelters/{id}/rooms` | 查询安置点房间占用 |
| POST | `/api/households` | 登记家庭与成员（成员身份标识全局唯一） |
| GET | `/api/households/{num}` | 查询家庭 |
| POST | `/api/checkins` | 家庭整体入住（幂等键 + 家庭号 + 安置点） |
| POST | `/api/households/{num}/transfers` | 家庭整体转移到另一安置点 |
| GET | `/api/households/{num}/stay` | 查询家庭当前有效入住 |
| GET | `/api/households/{num}/events` | 查询家庭入住/转移事件 |

统一错误响应格式：`{"timestamp": ..., "status": ..., "code": ..., "message": ...}`，
常见 `code`：`VALIDATION_FAILED`(400)、`HOUSEHOLD_NOT_FOUND`/`SHELTER_NOT_FOUND`/`NO_ACTIVE_STAY`(404/409)、
`IDEMPOTENCY_CONFLICT`/`HOUSEHOLD_ALREADY_CHECKED_IN`(409)、`NO_SUITABLE_ROOM`/`SAME_SHELTER_TRANSFER`(422)。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test
