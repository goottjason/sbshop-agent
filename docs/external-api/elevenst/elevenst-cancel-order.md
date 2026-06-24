# 11번가 주문취소

> **API 존재 여부**: 없음
> **방식**: 판매자 센터에서 수동 처리
> **구현**: `UnsupportedOperationException` 발생

---

## 1. 개요

11번가는 주문취소를 위한 공개 REST API를 제공하지 않음. 주문취소는 **11번가 판매자 관리 페이지**에서 수동으로 처리해야 함.

### 시스템 동작

```java
// ElevenstOrderAdapter.java
@Override
public void cancelOrder(MarketCredential credential, Order order) {
    throw new UnsupportedOperationException(
        "11번가 주문취소는 판매자 센터에서 수동 처리 필요: order=" + order.getMarketOrderNo());
}
```

`cancelOrder()` 호출 시 `UnsupportedOperationException`이 발생하며, 운영자가 직접 11번가 판매자 센터에서 취소 처리.

---

## 2. 수동 처리 절차

### 2.1 판매자 센터 접속

```
URL: https://seller.11st.co.kr
```

### 2.2 주문 관리

1. **주문/배송관리** > **주문관리** 메뉴 이동
2. 취소할 주문 검색 (주문번호 입력)
3. 주문 상세 페이지에서 **취소요청** 버튼 클릭

### 2.3 취소 사유 입력

- 취소 사유 선택 (품절, 가격 변동, 구매자 요청 등)
-必要시 상세 사유 입력
- 취소 확정

---

## 3. 대체 방안 (미구현)

향후 다음 방안을 통해 자동화 가능:

### 방안 A: 판매자 센터 스크래핑 (Selenium)

ESM+와 동일한 방식으로 판매자 센터 페이지를 Selenium으로 제어:
1. 로그인
2. 주문 관리 페이지 이동
3. 주문 검색
4. 취소 버튼 클릭
5. 사유 입력 + 확인

**장점**: 별도 API 불필요
**단점**: 페이지 구조 변경 시 깨질 수 있음, 세션 관리 필요

### 방안 B: 11번가 취소 API 추가 기대

11번가가 향후 취소 REST API를 추가할 수 있음. 추가 시 `ElevenstOrderApiPort`에 메서드 추가 필요.

---

## 4. 상태 매핑

11번가 취소 관련 상태 코드:

| 상태 코드 | 설명         | 내부 상태    |
|----------|-------------|-------------|
| 2010     | 주문취소     | `CANCELED`  |
| 2020     | 취소완료     | `CANCELED`  |

---

## 5. 소스 코드 참조

| 파일 위치                              | 설명                     |
|---------------------------------------|--------------------------|
| `ElevenstOrderAdapter.java`           | `cancelOrder()` 구현     |
| `MarketOrderPort.java`               | 기본 `cancelOrder()` 정의 |
| `OrderService.java`                   | `cancelOrderToMarketplace()` |
