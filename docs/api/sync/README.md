# 주문 동기화 API

## Base URL
```
/api/v1/orders/sync
```

## 엔드포인트

### 1. 쿠팡 주문 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/coupang` |
| **설명** | 쿠팡 주문 데이터를 백그라운드에서 동기화합니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "쿠팡 주문 동기화가 백그라운드에서 시작되었습니다."
}
```

**Error Response (500 Internal Server Error)**
```json
{
  "success": false,
  "message": "쿠팡 주문 동기화 실패: 에러 메시지"
}
```

---

### 2. 스마트스토어 주문 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/smartstore` |
| **설명** | 네이버 스마트스토어 주문 데이터를 백그라운드에서 동기화합니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "스마트스토어 주문 동기화가 백그라운드에서 시작되었습니다."
}
```

---

### 3. 11번가 주문 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/elevenstreet` |
| **설명** | 11번가 주문 데이터를 백그라운드에서 동기화합니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "11번가 주문 동기화가 백그라운드에서 시작되었습니다."
}
```

---

### 4. ESM+(G마켓/옥션) 주문 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/esmplus` |
| **설명** | ESM+(G마켓/옥션) 주문 데이터를 백그라운드에서 동기화합니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "ESM+(G마켓/옥션) 주문 동기화가 백그라운드에서 시작되었습니다."
}
```

---

### 5. 쿠팡 정산 데이터 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/coupang/settlement` |
| **설명** | 쿠팡 정산 데이터를 백그라운드에서 동기화합니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "쿠팡 정산 데이터 동기화가 백그라운드에서 시작되었습니다."
}
```

---

### 6. 통관 상태 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/customs` |
| **설명** | 통관 상태 데이터를 동기화합니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "통관 상태 동기화가 완료되었습니다."
}
```

---

### 7. 동기화 상태 조회

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/v1/orders/sync/status` |
| **설명** | 현재 각 마켓별 동기화 상태를 조회합니다. |

**Response (200 OK)**
```json
{
  "COUPANG": {
    "status": "IDLE",
    "lastSyncTime": "2024-06-15T10:30:00",
    "syncedCount": 150
  },
  "SMARTSTORE": {
    "status": "SYNCING",
    "lastSyncTime": "2024-06-15T10:25:00",
    "syncedCount": 0
  }
}
```

---

### 8. ESM+ 로그인 테스트

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/esmplus/test` |
| **설명** | ESM+ 로그인 및 주문 스크래핑을 테스트합니다. |

**Request Body**

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `masterId` | `String` | No | `shouldbeshop` | ESM+ 마스터 ID |
| `password` | `String` | No | - | ESM+ 비밀번호 |
| `fromDate` | `String` | No | `2024-06-01` | 조회 시작일 (yyyy-MM-dd) |
| `toDate` | `String` | No | `2024-06-14` | 조회 종료일 (yyyy-MM-dd) |

**Request Example**
```json
{
  "masterId": "shouldbeshop",
  "password": "password123",
  "fromDate": "2024-06-01",
  "toDate": "2024-06-14"
}
```

**Response (200 OK)**
```json
{
  "success": true,
  "orders": [...],
  "message": "스크래핑 완료"
}
```

---

### 9. ESM+ 주문 스크래핑 테스트

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/sync/esmplus/scrape` |
| **설명** | ESM+ 주문 스크래핑을 테스트합니다. |

**Request Body**

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `masterId` | `String` | No | `shouldbeshop` | ESM+ 마스터 ID |
| `password` | `String` | No | - | ESM+ 비밀번호 |
| `fromDate` | `String` | No | `2024-06-01` | 조회 시작일 (yyyy-MM-dd) |
| `toDate` | `String` | No | `2024-06-14` | 조회 종료일 (yyyy-MM-dd) |

**Response (200 OK)**
```json
{
  "success": true,
  "orders": [...],
  "message": "스크래핑 완료"
}
```
