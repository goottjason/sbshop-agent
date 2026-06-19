# 공통 코드 API

## Base URL
```
/api/v1/common/codes
```

## 엔드포인트

### 1. 공통 코드 조회

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/v1/common/codes` |
| **설명** | 시스템에서 사용하는 공통 코드(열거형) 목록을 조회합니다. |

**Response (200 OK)**
```json
{
  "marketType": [
    { "code": "COUPANG", "name": "쿠팡" },
    { "code": "SMARTSTORE", "name": "스마트스토어" },
    { "code": "ELEVENST", "name": "11번가" },
    { "code": "ESMPLUS", "name": "ESM+(G마켓/옥션)" }
  ],
  "shippingStatus": [
    { "code": "NEW", "name": "결제완료" },
    { "code": "PREPARING", "name": "배송준비" },
    { "code": "SHIPPED", "name": "배송중" },
    { "code": "DELIVERED", "name": "배송완료" },
    { "code": "CANCELLED", "name": "취소됨" }
  ],
  "customsStatus": [
    { "code": "PENDING", "name": "통관대기" },
    { "code": "IN_PROGRESS", "name": "통관중" },
    { "code": "COMPLETED", "name": "통관완료" },
    { "code": "REJECTED", "name": "통관반려" }
  ],
  "recordStatus": [
    { "code": "ACTIVE", "name": "활성" },
    { "code": "INACTIVE", "name": "비활성" },
    { "code": "DELETED", "name": "삭제됨" }
  ]
}
```

**코드 설명**

### MarketType (마켓 타입)
| 코드 | 이름 | 설명 |
|------|------|------|
| `COUPANG` | 쿠팡 | 쿠팡 마켓플레이스 |
| `SMARTSTORE` | 스마트스토어 | 네이버 스마트스토어 |
| `ELEVENST` | 11번가 | 11번가 마켓플레이스 |
| `ESMPLUS` | ESM+ | G마켓/옥션 통합 플랫폼 |

### ShippingStatus (배송 상태)
| 코드 | 이름 | 설명 |
|------|------|------|
| `NEW` | 결제완료 | 주문 접수 및 결제 완료 |
| `PREPARING` | 배송준비 | 상품 준비 중 |
| `SHIPPED` | 배송중 | 상품 발송 완료 |
| `DELIVERED` | 배송완료 | 상품 인수 확인 |
| `CANCELLED` | 취소됨 | 주문 취소 |

### CustomsStatus (통관 상태)
| 코드 | 이름 | 설명 |
|------|------|------|
| `PENDING` | 통관대기 | 통관 절차 대기 중 (통관번호 입력 후 검증 대기) |
| `VALID` | 정상 | 통관 검증 완료 (이름, 전화번호, 우편번호 일치) |
| `INVALID_PCCC` | 통관번호 불일치 | 납세의무자명이 개인통관고유부호 성명과 불일치 또는 부호 미존재 |
| `INVALID_PHONE` | 전화번호 불일치 | 납세의무자 전화번호 불일치 |
| `INVALID_ZIPCODE` | 우편번호 불일치 | 입력 우편번호가 통관고유부호 우편번호와 불일치 |

### RecordStatus (레코드 상태)
| 코드 | 이름 | 설명 |
|------|------|------|
| `ACTIVE` | 활성 | 정상 상태 |
| `INACTIVE` | 비활성 | 비활성 상태 |
| `DELETED` | 삭제됨 | 삭제 처리됨 |
