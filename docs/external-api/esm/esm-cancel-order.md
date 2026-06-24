# ESM+(G마켓/옥션) 주문취소

> **방식**: Selenium 웹 스크래핑 자동화
> **페이지**: `https://www.esmplus.com/Home/v2/order-integration`
> **주의**: ESM+는 주문취소 REST API를 제공하지 않음. 웹 페이지 버튼 클릭 방식으로 자동화.

---

## 1. 개요

ESM+(G마켓/옥션)은 주문취소를 위한 공개 REST API가 없음. therefore, Selenium WebDriver로 판매자 관리 페이지에 접속하여 취소처리 버튼을 클릭하고 취소 사유를 입력하는 방식으로 구현.

### 동작 방식

```
1. 로그인 (https://signin.esmplus.com/login)
2. 주문통합관리 페이지 접속
3. iframe 전환 (innerIFrame)
4. 대상 주문 체크박스 선택 (siteOrderNo 기준)
5. 취소처리 버튼 클릭
6. 취소 사유 입력
7. 확인 다이얼로그 처리
```

---

## 2. Port 인터페이스

```java
void cancelOrders(String masterId, String password, List<String> siteOrderNos, String reason);
```

| 파라미터       | 타입            | 설명                    |
|---------------|----------------|------------------------|
| `masterId`    | `String`       | ESM+ 마스터 ID          |
| `password`    | `String`       | 비밀번호                 |
| `siteOrderNos`| `List<String>` | 취소할 사이트 주문 번호 목록 |
| `reason`      | `String`       | 취소 사유                |

---

## 3. Selenium 자동화 상세

### 3.1 로그인

기존 `loginAndCreateDriver()` 메서드 재사용 (발주확인과 동일).

### 3.2 주문 선택 및 취소처리

```javascript
// 체크박스 선택 (발주확인과 동일)
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
// 취소처리 버튼 클릭
var buttons = document.querySelectorAll('button');
for (var i = 0; i < buttons.length; i++) {
  var text = buttons[i].textContent.trim();
  if (text.indexOf('취소처리') >= 0 || text.indexOf('주문취소') >= 0) {
    buttons[i].click();
    break;
  }
}
```

### 3.3 취소 사유 입력

```javascript
// 취소 사유 textarea/input에 값 입력
var inputs = document.querySelectorAll('textarea, input[type=text]');
for (var i = 0; i < inputs.length; i++) {
  var el = inputs[i];
  var placeholder = (el.placeholder || '').toLowerCase();
  var name = (el.name || '').toLowerCase();
  if (placeholder.indexOf('사유') >= 0 || name.indexOf('reason') >= 0) {
    el.value = '{reason}';
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    break;
  }
}
```

### 3.4 확인 다이얼로그

발주확인과 동일한 방식.

---

## 4. 흐름도

```
┌─────────────────────────────────────────────┐
│  cancelOrders() 호출                         │
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
│  3. 취소처리 실행                              │
│     ├─ 취소처리 버튼 클릭                       │
│     ├─ 2초 대기                               │
│     ├─ 취소 사유 입력                           │
│     ├─ 1초 대기                               │
│     └─ 확인 다이얼로그 처리                     │
│                                              │
│  4. 드라이버 종료                              │
└─────────────────────────────────────────────┘
```

---

## 5. 주의사항

### 취소 가능 상태

ESM+에서 취소 가능한 상태는 다음 조건을 만족해야 함:
- 결제 완료 상태 (`deliveryStatusCode = 1010`)
- 발주확인 전 상태

이미 배송이 시작된 주문은 취소 불가. 반품/교환 절차 필요.

### 취소 사유 필수

ESM+는 취소 처리 시 사유 입력이 필수. `reason` 파라미터가 비어있으면 기본값 `"판매자 취소"` 사용.

### 변경 이력

ESM+ 페이지 구조 변경 시 스크래핑 실패 가능. 현재 구현은 다음 셀렉터를 사용:
- 체크박스: `tr[data-order-no] input[type=checkbox]`
- 버튼: `button` 태그의 `textContent` 매칭
- 사유 입력: `textarea` 또는 `input[type=text]`의 `placeholder`/`name` 매칭

---

## 6. 소스 코드 참조

| 파일 위치                              | 설명                     |
|---------------------------------------|--------------------------|
| `EsmplusOrderApiPort.java`            | 포트 인터페이스            |
| `EsmplusOrderApiPortImpl.java`        | Selenium 구현             |
| `EsmplusOrderAdapter.java`            | 어댑터 (MarketOrderPort)  |
