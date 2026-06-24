# ESM+(G마켓/옥션) 발주확인

> **방식**: Selenium 웹 스크래핑 자동화
> **페이지**: `https://www.esmplus.com/Home/v2/order-integration`
> **주의**: ESM+는 발주확인 REST API를 제공하지 않음. 웹 페이지 버튼 클릭 방식으로 자동화.

---

## 1. 개요

ESM+(G마켓/옥션)은 발주확인을 위한 공개 REST API가 없음. therefore, Selenium WebDriver로 판매자 관리 페이지에 접속하여 발주확인 버튼을 클릭하는 방식으로 구현.

### 동작 방식

```
1. 로그인 (https://signin.esmplus.com/login)
2. 주문통합관리 페이지 접속
3. iframe 전환 (innerIFrame)
4. 대상 주문 체크박스 선택 (siteOrderNo 기준)
5. 발주확인 버튼 클릭
6. 확인 다이얼로그 처리
```

---

## 2. Port 인터페이스

```java
void confirmOrders(String masterId, String password, List<String> siteOrderNos);
```

| 파라미터       | 타입            | 설명                    |
|---------------|----------------|------------------------|
| `masterId`    | `String`       | ESM+ 마스터 ID          |
| `password`    | `String`       | 비밀번호                 |
| `siteOrderNos`| `List<String>` | 발주확인할 사이트 주문 번호 목록 |

---

## 3. Selenium 자동화 상세

### 3.1 로그인

기존 `loginAndCreateDriver()` 메서드 재사용:

```
URL: https://signin.esmplus.com/login
```

1. ESM 탭 클릭 (`button.button__tab--esm`)
2. 마스터 ID 입력 (`#typeMemberInputId01`)
3. 비밀번호 입력 (`#typeMemberInputPassword01`)
4. 로그인 버튼 클릭 (`button.button--blue`)
5. 5초 대기

### 3.2 주문통합관리 페이지

```
URL: https://www.esmplus.com/Home/v2/order-integration
```

1. 페이지 접속 후 5초 대기
2. iframe(`innerIFrame`) 전환
3. 검색 버튼 클릭으로 주문 목록 로드

### 3.3 주문 선택 및 발주확인

```javascript
// 체크박스 선택 (JS executeScript)
var rows = document.querySelectorAll('tr');
for (var i = 0; i < rows.length; i++) {
  var orderNo = rows[i].getAttribute('data-order-no') || '';
  if (orderNo === '{siteOrderNo}') {
    var checkbox = rows[i].querySelector('input[type=checkbox]');
    if (checkbox && !checkbox.checked) checkbox.click();
    break;
  }
}
```

```javascript
// 발주확인 버튼 클릭
var buttons = document.querySelectorAll('button');
for (var i = 0; i < buttons.length; i++) {
  var text = buttons[i].textContent.trim();
  if (text.indexOf('발주확인') >= 0 || text.indexOf('주문확인') >= 0) {
    buttons[i].click();
    break;
  }
}
```

### 3.4 확인 다이얼로그

```javascript
// 모달 확인 버튼 클릭
var modals = document.querySelectorAll('.modal, [role=dialog]');
for (var i = 0; i < modals.length; i++) {
  var btns = modals[i].querySelectorAll('button');
  for (var j = 0; j < btns.length; j++) {
    if (btns[j].textContent.trim() === '확인' || btns[j].textContent.trim() === '예') {
      btns[j].click();
      break;
    }
  }
}
```

---

## 4. 흐름도

```
┌─────────────────────────────────────────────┐
│  confirmOrders() 호출                        │
├─────────────────────────────────────────────┤
│  1. loginAndCreateDriver()                  │
│     ├─ 로그인 페이지 접속                     │
│     ├─ ID/PW 입력 + 로그인                    │
│     └─ 주문통합관리 페이지 접속 + iframe 전환   │
│                                              │
│  2. 대상 주문 선택                             │
│     ├─ siteOrderNo 별 체크박스 클릭            │
│     └─ 0.5초 대기                             │
│                                              │
│  3. 발주확인 실행                              │
│     ├─ 발주확인 버튼 클릭                       │
│     ├─ 3초 대기                               │
│     └─ 확인 다이얼로그 처리                     │
│                                              │
│  4. 드라이버 종료                              │
└─────────────────────────────────────────────┘
```

---

## 5. 주의사항

### 페이지 구조 변경 리스크

ESM+는 SPA 기반이며, 페이지 구조가 변경될 수 있음. 체크박스/버튼 선택 로직이 실패하면 에러가 발생함.

### 세션 관리

- `confirmOrders`는 독립 드라이버 세션을 생성하고 종료
- `cachedDetailDriver`와 공유하지 않음 ( 독립 실행 보장 )
- 로그인 실패 시 `RuntimeException` 발생

### rate limit

Selenium 자동화이므로 별도 rate limit 없음. 다만 서버 부하를 고려하여 주문 간 0.5초 sleep 적용.

### 병렬 실행 불가

Selenium 드라이버는 단일 세션에서 순차 실행. 병렬 처리 시 세션 충돌 발생 가능.

---

## 6. 소스 코드 참조

| 파일 위치                              | 설명                     |
|---------------------------------------|--------------------------|
| `EsmplusOrderApiPort.java`            | 포트 인터페이스            |
| `EsmplusOrderApiPortImpl.java`        | Selenium 구현             |
| `EsmplusOrderAdapter.java`            | 어댑터 (MarketOrderPort)  |
