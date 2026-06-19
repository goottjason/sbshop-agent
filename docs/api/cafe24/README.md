# 카페24 인증 API

## Base URL
```
/api/admin/sync/cafe24
```

## 엔드포인트

### 1. 카페24 인증 콜백

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/admin/sync/cafe24/auth/callback` |
| **설명** | 카페24 OAuth 인증 코드를 받아 최초 토큰을 발급합니다. 브라우저 주소창에서 직접 접근하여 사용합니다. |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `code` | `String` | Yes | 카페24에서 발급한 인증 코드 |

**사용법**
1. 카페24 개발자 센터에서 애플리케이션 등록 후 Redirect URI 설정
2. 카페24 인증 URL로 브라우저 이동하여 인증 코드 획득
3. 아래 URL을 브라우저 주소창에 입력:
```
https://your-domain.com/api/admin/sync/cafe24/auth/callback?code=받아온코드
```

**Response (200 OK)**
```
✅ Cafe24 최초 인증이 완료되었습니다! 이제 서버가 알아서 평생 토큰을 갱신합니다.
```

**Error Response (500 Internal Server Error)**
```
❌ 인증 실패: 에러 메시지
```

**참고**
- 이 엔드포인트는 최초 인증 시에만 사용됩니다.
- 최초 인증 후 시스템이 자동으로 토큰을 갱신합니다.
- 관리자(Admin) 전용 API입니다.
