# 상품 동기화 API

## Base URL
```
/api/v1/products
```

## 엔드포인트

### 1. 상품 재고 동기화

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/products/sync/stock` |
| **설명** | 결제완료(NEW) 및 배송준비(PREPARING) 상태인 주문에 포함된 상품의 재고를 외부 마켓에서 동기화합니다. 백그라운드에서 비동기로 실행됩니다. |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "Targeted stock sync for PREPARING orders started in background"
}
```

**Error Response (500 Internal Server Error)**
```json
{
  "success": false,
  "message": "에러 메시지"
}
```

**참고**
- 이 API는 백그라운드에서 비동기로 실행됩니다.
- 결제완료(NEW)와 배송준비(PREPARING) 상태의 주문에 포함된 상품만 대상으로 합니다.
- 상품 ID는 중복이 제거된 후 동기화됩니다.
