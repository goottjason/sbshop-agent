# 마켓 자격증명 API

## Base URL
```
/api/v1/market-credentials
```

## 엔드포인트

### 1. 전체 마켓 자격증명 조회

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/v1/market-credentials` |
| **설명** | 등록된 모든 마켓의 자격증명 정보를 조회합니다. |

**Response (200 OK)**
```json
[
  {
    "id": 1,
    "marketType": "COUPANG",
    "clientId": "client_id_123",
    "accessKey": "access_key_***",
    "secretKey": "secret_key_***",
    "redirectUri": "https://callback.example.com",
    "hasRefreshToken": true
  },
  {
    "id": 2,
    "marketType": "SMARTSTORE",
    "clientId": "client_id_456",
    "accessKey": "access_key_***",
    "secretKey": "secret_key_***",
    "redirectUri": "https://callback.example.com",
    "hasRefreshToken": false
  }
]
```

---

### 2. 특정 마켓 자격증명 조회

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/v1/market-credentials/{marketType}` |
| **설명** | 특정 마켓의 자격증명 정보를 조회합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `marketType` | `MarketType` | 마켓 타입 (COUPANG, SMARTSTORE, ELEVENST, ESMPLUS) |

**Response (200 OK)**
```json
{
  "id": 1,
  "marketType": "COUPANG",
  "clientId": "client_id_123",
  "accessKey": "access_key_***",
  "secretKey": "secret_key_***",
  "redirectUri": "https://callback.example.com",
  "hasRefreshToken": true
}
```

**Error Response (404 Not Found)**

---

### 3. 마켓 자격증명 저장/수정

| 항목 | 내용 |
|------|------|
| **Method** | `PUT` |
| **URL** | `/api/v1/market-credentials/{marketType}` |
| **설명** | 마켓 자격증명 정보를 저장하거나 수정합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `marketType` | `MarketType` | 마켓 타입 (COUPANG, SMARTSTORE, ELEVENST, ESMPLUS) |

**Request Body (MarketCredentialSaveCommand)**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `clientId` | `String` | Yes | 클라이언트 ID |
| `accessKey` | `String` | No | 접근 키 |
| `secretKey` | `String` | No | 시크릿 키 |
| `redirectUri` | `String` | No | 리다이렉트 URI |

**Request Example**
```json
{
  "clientId": "client_id_123",
  "accessKey": "access_key_456",
  "secretKey": "secret_key_789",
  "redirectUri": "https://callback.example.com"
}
```

**Response (200 OK)**
```json
{
  "id": 1,
  "marketType": "COUPANG",
  "clientId": "client_id_123",
  "accessKey": "access_key_***",
  "secretKey": "secret_key_***",
  "redirectUri": "https://callback.example.com",
  "hasRefreshToken": false
}
```
