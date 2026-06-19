# SSE 알림 API

## Base URL
```
/api/v1/notifications
```

## 엔드포인트

### 1. SSE 구독

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/v1/notifications/subscribe` |
| **설명** | Server-Sent Events(SSE)를 통해 실시간 알림을 구독합니다. |
| **Content-Type** | `text/event-stream` |

**Response (200 OK)**
```
event: INIT
data: Connected to SSE
```

**이벤트 유형**

| 이벤트 | 설명 | 데이터 형식 |
|--------|------|-------------|
| `INIT` | 연결 성공 시 | `Connected to SSE` |
| `SYNC_COMPLETED` | 동기화 성공 시 | `{MARKET_TYPE}\|success` |
| `SYNC_FAILED` | 동기화 실패 시 | `{MARKET_TYPE}\|fail\|{에러 메시지}` |

**이벤트 예시**
```
event: SYNC_COMPLETED
data: COUPANG|success

event: SYNC_FAILED
data: SMARTSTORE|fail|API rate limit exceeded
```

**참고**
- 연결 타임아웃: 60초
- 동시 연결 관리: CopyOnWriteArrayList 사용
- 연결 종료/타임아웃/오류 시 자동으로 클라이언트 제거
