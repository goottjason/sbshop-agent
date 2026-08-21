# 버그·백로그 — core/product · core/sourcing (2026-08-21)

리팩토링(구조 변경만) 중 발견. **B-1 한 건만 리더 승인 하에 캠페인 내에서 정정했고, 나머지는
기록만 하고 수정하지 않았다.** defect-ledger 등재 여부는 리더가 판단.

| # | 항목 | 상태 |
|---|---|---|
| B-1 | 소스에 raw NUL — grep이 파일을 통째로 놓침 | ✅ 정정 완료(리더 승인) |
| B-2 | 서베이 데드코드 오판 — `IngredientAliasSeed` | ✅ 삭제 스킵 확정(리더 재검증) |
| B-3 | `SourcingAgentFactory`/`SourcingAgent` 고아 쌍 | 기록만 |
| B-4 | 신규 등록가 상향 편향 | 기록만 |
| B-5 | `DetailHtmlBuilder` 미사용 파라미터 | 기록만 |

---

## B-1. ✅ 소스 파일에 NUL 바이트 — grep이 파일 전체를 통째로 놓친다 (**리더 승인으로 캠페인 내 정정 완료**)

> **상태: 해결됨 (2026-08-21).** 리더가 "컴파일 산출 문자열이 바이트 동일하므로 행위 변경이 아니라
> 소스 표현 정정(구조 변경)"으로 승인 → raw 0x00을 `"\0"` 이스케이프로 교체 완료.
> 검증 결과는 이 항목 맨 아래 **검증** 절 참조.

**위치:** `backend/core/src/main/java/com/sbshop/agent/core/application/sourcing/customs/BannedIngredientSyncService.java:88` (수정 전 기준)

```java
private String identityKey(String nameKo, String nameEn) {
    return (nameKo == null ? "" : nameKo.trim()) + "<NUL>" + (nameEn == null ? "" : nameEn.trim());
}
```

구분자 문자열 리터럴에 **이스케이프(`"\0"`)가 아니라 raw NUL 바이트(0x00)** 가 직접 박혀 있다.

**증상 (실측):**
- `file`이 이 파일을 `data`(바이너리)로 판정한다.
- `grep`이 이 파일을 바이너리로 보고 **어떤 패턴도 매칭시키지 않는다.** 매칭 출력도, "Binary file matches"
  경고도 없이 조용히 rc=1을 반환한다. `LC_ALL=C`로도 동일.
  ```
  $ grep -c "SyncResult" BannedIngredientSyncService.java   # 파일에 5회 이상 등장
  rc=1
  ```
- 즉 **이 파일은 코드베이스 전체 grep 검색에서 존재하지 않는 파일처럼 취급된다.**

**실제로 발생한 피해:** `survey-backend.md`가 `IngredientAliasSeed`를 "고신뢰 데드코드, 삭제 안전"으로
등재했다(§B-2 참조). 그 근거인 "실제 호출부 전무"는 grep 결과인데, 유일한 호출부가 바로 이 파일
59번 줄이다. 지시대로 삭제했다면 **컴파일이 깨지고 통관 게이트의 별칭 보강이 사라졌을 것**이다.
백엔드 전체 스캔 결과 NUL을 가진 java 파일은 이 1건뿐이다(`backend/**/*.java`, build 제외).

**영향도:** 높음(도구 신뢰성). 기능 자체는 정상 — NUL은 문자열 리터럴 안이라 Java는 정상 컴파일하고,
구분자로서도 이름에 등장할 수 없는 문자라 의도는 오히려 합리적이다. 문제는 **표기 방식**이다.

**적용한 조치:** raw 0x00 → `"\0"` (Java 8진 이스케이프, U+0000). 바이트 단위 치환 1건.

```java
return (nameKo == null ? "" : nameKo.trim()) + "\0" + (nameEn == null ? "" : nameEn.trim());
```

### 검증 (4단계 전부 통과)

1. **소스에 raw NUL 0건** — 백엔드 전체 `*.java` python 재스캔: NUL 보유 파일 0개.
2. **`:core:compileJava` / `:core:compileTestJava` BUILD SUCCESSFUL.**
3. **grep이 이제 파일을 본다** — `file`이 `Java source, Unicode text, UTF-8 text`로 판정.
   ```
   $ grep -c 'SyncResult' BannedIngredientSyncService.java     → 7   (이전: rc=1, 0건)
   $ grep -n 'IngredientAliasSeed' BannedIngredientSyncService.java
     49: String aliases = String.join(",", IngredientAliasSeed.aliasesFor(dto.nameKo()));
   ```
   저장소 전체 grep에서도 B-2의 호출부가 정상적으로 잡힌다.
4. **컴파일 산출물에 U+0000이 그대로 남아 있다 (행위 동일의 직접 증거).**
   클래스 파일 상수풀:
   ```
   #275 = String  #276   // \u0001\u0000\u0001
   ```
   `StringConcatFactory` 레시피로, `\u0001`이 인자 자리표시자이고 그 사이 **리터럴 U+0000**이
   구분자다 — 즉 `arg1 + "\0" + arg2`. Modified UTF-8 인코딩(`0xC0 0x80`)도 클래스 파일에
   정확히 1회 존재한다.
   ※ 처음엔 클래스 파일에서 raw `0x00`을 찾아 "0건"이 나왔는데, 이는 **JVM 상수풀이 Modified UTF-8이라
   U+0000을 raw 0x00이 아닌 `0xC0 0x80`으로 인코딩**하기 때문이다. 검증 패턴이 틀렸던 것이지
   문자열이 바뀐 것이 아니었다.

**부수 권고 (유효):** grep 결과를 근거로 "미참조 → 삭제"를 판단할 때는, 최소한 삭제 직전 컴파일
또는 grep 외 수단(python / `rg --text`)으로 교차 확인할 것. 이번 캠페인에서 실제로 한 번 걸렸다.

---

## B-2. ✅ 서베이 오류 정정 — `IngredientAliasSeed`는 데드코드가 아니다 (**삭제하지 않음 — 리더 승인으로 확정**)

> **상태: 종결 (2026-08-21).** 리더가 직접 재검증해 삭제 스킵을 정당하다고 판정했다
> (NUL 1바이트 확인 · `IngredientAliasSeed` 참조 실존 확인 · **삭제 금지 확정**).
> 오판의 원인이던 B-1은 같은 승인으로 정정 완료되어, 이제 이 호출부가 grep에도 정상적으로 잡힌다.
> 나머지 고신뢰 2건(`BusinessDayCalculator`·`UnipassUpdateRequest`)은 리더가 `grep -ra`로 재검증한
> 결과 **진짜 미참조**로 확인되어 삭제를 유지한다. 전 백엔드에서 NUL 파일은 B-1 1건뿐임도 확인됐다.

**위치:** `core/application/sourcing/customs/IngredientAliasSeed.java`
**서베이 주장:** `survey-backend.md:17` — "실제 호출부는 전무", "고신뢰 데드코드 3건 … 전부 확인 완료, 삭제 안전"
**실제:**

```
backend/core/src/main/java/com/sbshop/agent/core/application/sourcing/customs/BannedIngredientSyncService.java:59:
    String aliases = String.join(",", IngredientAliasSeed.aliasesFor(dto.nameKo()));
```

같은 패키지라 import가 없고, 호출 파일이 B-1 때문에 grep에 잡히지 않아 "미참조"로 오판됐다.
파이썬으로 전 소스를 재스캔해 확인했다.

**기능적 중요도:** 식약처 원천은 대표명 하나만 준다. 이 시드가 별칭("요힘빈"/"요힘베"/"Yohimbe" 등)을
보강해 성분표 매칭률을 올린다. 삭제하면 통관 게이트가 **차단 대상 상품을 조용히 놓치기 시작**한다
(BLOCKED가 되어야 할 상품이 PASS로 흘러감 — 실패가 눈에 띄지 않는 종류).

**조치:** 담당 지시("IngredientAliasSeed 삭제")를 **수행하지 않았다.** 주석 제거만 적용.
리더 재검증으로 삭제 금지가 확정됐다(위 상태 박스).
`survey-backend.md:17`의 해당 항목과 "고신뢰 데드코드 3건" 요약 문구는 여전히 정정 대상이다 —
실제 고신뢰 데드코드는 3건이 아니라 **2건**(`BusinessDayCalculator`·`UnipassUpdateRequest`)이다.

---

## B-3. 🟡 `SourcingAgentFactory` + `SourcingAgent` — 구현체가 없는 고아 서브시스템 (기록만, 삭제 금지)

**위치:** `core/domain/sourcing/component/SourcingAgentFactory.java`, `SourcingAgent.java`

- `SourcingAgentFactory`는 `@Component`로 등록되어 `List<SourcingAgent>`를 주입받는다.
- 그런데 `SourcingAgent`를 `implements` 하는 클래스가 **코드베이스 전체에 하나도 없다**
  (python 전수 스캔으로 재확인 — B-1의 grep 함정을 배제하고 검증했다).
- `SourcingAgentFactory` 자체를 주입/호출하는 코드도 없다.

**런타임 거동:** Spring이 빈 리스트를 주입하므로 팩토리는 정상 기동하지만, 만약 누군가 호출하면
`getAgentByUrl()`이 항상 `IllegalArgumentException("지원하지 않는 소싱 URL입니다: …")`을 던진다.
"URL을 지원하지 않는다"는 메시지는 원인을 오도한다 — 실제 원인은 **에이전트가 하나도 등록되지 않은 것**이다.

**추정:** 현행 소싱 크롤은 `BestsellerCrawlerPort`/`ProductDetailCrawlerPort`(Scrapling 사이드카)가
담당하므로, 이 쌍은 그 이전 설계의 잔재로 보인다.

**심각도:** 낮음(현재 미사용) / 정리 가치는 중간(죽은 추상화가 신규 개발자를 오도).
**조치:** 지시대로 삭제하지 않고 기록만. 삭제 여부는 원장에서 판단.

---

## B-4. 🟡 `MarketSalePriceResolver` Javadoc이 지목한 구조적 편향 (기능 이슈, 코드 정상)

**위치:** `core/application/product/MarketSalePriceResolver#resolveForProduct(Product, MarketType)`

쿠폰율·최소마진은 배치 실행 파라미터라 상품에 저장되지 않는다. 그래서 오버라이드 없는 신규 등록
경로는 **쿠폰 미반영분만큼 판매가를 높게** 산정한다(실측: 동기화 51,400원 vs 등록 62,200원).
원래는 정기 재가격 배치가 이 값을 내려줬지만 **그 배치는 D-093 사용자 결정으로 비활성**이다.
따라서 호출자가 `MarketSalePriceOverrides`를 넘기지 않으면 **편향이 등록 시점 그대로 영구히 남는다.**

코드 자체는 의도대로 동작하며 이미 주석에 명시돼 있던 알려진 트레이드오프다(살베이지에 보존).
다만 "재가격 배치가 나중에 고쳐준다"는 전제가 이미 무효이므로, 호출부 전수가 오버라이드를
넘기고 있는지 확인할 가치가 있다. 심각도는 호출부 실태에 달렸다.

---

## B-5. 🟢 `DetailHtmlBuilder`의 미사용 파라미터

**위치:** `core/application/sourcing/enrich/DetailHtmlBuilder` — `hostedImages` 파라미터

현재 쓰이지 않는다(템플릿이 이미지 배치를 담당). 원 주석은 "향후 본문 중간 삽입형 레이아웃 대비"로
의도적 보존이라 밝히고 있어 데드코드로 삭제하지 않았다(살베이지에 보존).
호출 시그니처 변경은 행위/API 변경이므로 이번 범위 밖. 향후 계획이 없다면 제거 후보.

---

## TODO 백로그

`core/**/product/**`, `core/**/sourcing/**` main+test 전체에서
`TODO` / `FIXME` / `XXX` / `HACK` / `@Deprecated` **0건**. 옮겨 적을 항목 없음.

---

## 미사용 private 멤버

휴리스틱 스캔 결과 18건이 걸렸으나 **전부 오탐**으로 확인 — 삭제 없음.
- `domain/product/vo/*`(ImageInfo·PriceInfo·LogisticsInfo·ProductSpec·SourcingInfo),
  `domain/sourcing/MarketDraft#draftId`: Lombok `@Getter`로 접근하는 JPA/VO 필드.
- `application/product/*Test`의 `productPersistTxService`·`imageDownloadClient`·`imageStorageClient`:
  `@Mock` + `@InjectMocks`로 주입되는 필드.
