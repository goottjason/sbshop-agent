# API 명세

SBShop Agent 시스템의 API 명세입니다.

## 도메인별 API 목록

| 도메인 | 설명 | 문서 |
|--------|------|------|
| **주문 관리** | 주문 조회, 수정, 삭제, 발송, 확정, 취소, 소싱/배송 정보 관리 | [order/README.md](./order/README.md) |
| **주문 동기화** | 각 마켓(쿠팡, 스마트스토어, 11번가, ESM+) 주문 데이터 동기화 | [sync/README.md](./sync/README.md) |
| **상품 동기화** | 상품 재고 동기화 | [product/README.md](./product/README.md) |
| **마켓 자격증명** | 마켓별 API 인증 정보 관리 | [market/README.md](./market/README.md) |
| **카페24 인증** | 카페24 OAuth 인증 처리 | [cafe24/README.md](./cafe24/README.md) |
| **SSE 알림** | 실시간 Server-Sent Events 알림 | [notification/README.md](./notification/README.md) |
| **공통 코드** | 시스템 공통 코드(열거형) 조회 | [common/README.md](./common/README.md) |

## API 베이스 URL

| 환경 | URL |
|------|-----|
| 개발 | `http://localhost:8080` |
| 운영 | `https://your-production-domain.com` |

## 인증

현재 별도의 인증 없이 CORS가 허용된 상태로 개발 중입니다. 운영 환경에서는 인증 구현이 필요합니다.

## 공통 응답 형식

### 성공 응답
```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다."
}
```

### 에러 응답
```json
{
  "success": false,
  "message": "에러 메시지"
}
```

## 참고사항

- 모든 API는 `@CrossOrigin(origins = "*")` 설정으로 로컬 프론트엔드(Vite) 개발을 지원합니다.
- 백그라운드 작업이 필요한 동기화 API는 비동기로 실행됩니다.
- SSE 알림을 통해 동기화 완료/실패 이벤트를 실시간으로 수신할 수 있습니다.
