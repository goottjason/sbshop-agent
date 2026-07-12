# SP-D: 신규 상품 등록 위저드 완결 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 크롤~DB저장에 그친 신규등록을 크롤→필드보정→가격/공급처→마켓등록의 완결된 위저드로 만든다.

**Architecture:** 백엔드 publish(`ProductPublishUseCase`)·크롤·이미지는 완성돼 있으므로 재사용. 백엔드는 3개 최소 변경(bulk가 productId 반환, sbCode 이중채번 수정, ProductSaveRequest에 rawCategory 추가)만 하고, 프론트 `ProductRegisterPage`를 antd Steps 위저드로 재구성해 보정·가격입력·마켓등록을 배선한다. 마켓 등록은 저장 후 기존 단건 `publishToMarket`를 상품×마켓 조합으로 루프 호출한다.

**Tech Stack:** Java 21, Spring Boot 3.5 (core/api), React 19/Vite/TS (antd), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- bulk 저장은 생성된 productId를 반환: `POST /api/v1/products/bulk` → `List<Long>`. `createBulk`는 이미 `List<Product>` 반환.
- sbCode 채번은 항목당 정확히 1회 — `getNextSbCodeSequence(prefix)`를 배치당 1회 호출해 시작 시퀀스를 얻고 로컬 증가. (impl: 반환값은 다음 가용 전체코드 예 `"250712IHB006"`; prefix 이후를 parseInt하면 시작 seq.)
- 마켓 등록은 저장 후 단건 `publishToMarket(id, marketType)` 루프(상품×마켓). 마켓별 성공/실패 개별 표면화(조용한 실패 금지, SP-A 원칙). bulk-publish API는 만들지 않음.
- marginRate·vendor는 위저드 **수동 입력**. `vendor`는 `VendorType` enum(`IHB, AMZ, FTN, COK, OCD, TES, VTB`) 드롭다운(기본 `IHB`). `ProductCreateCommand`에 supplier 필드가 없으므로 `GET /suppliers`는 배선하지 않음(다른 개념 — 범위 밖). FeePolicy 자동계산 범위 밖.
- rawCategory→마켓 카테고리코드 매핑은 범위 밖(백엔드에도 없음). 위저드는 rawCategory 문자열만 전송.
- DDL 없음. 신규 의존성 없음.
- 커밋 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: bulk API가 productId 목록 반환

**Files:**
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductSourcingController.java` (`saveProductsBulk` ~라인 60-77)
- Test: `backend/api/src/test/java/com/sbshop/agent/api/controller/ProductSourcingBulkTest.java`

**Interfaces:**
- Consumes: `productCreateUseCase.createBulk(List<ProductCreateCommand>) : List<Product>` (기존), `Product.getId() : Long`.
- Produces: `POST /api/v1/products/bulk` → `ResponseEntity<List<Long>>` (생성 productId 목록).

- [ ] **Step 1: 실패 테스트 작성**

`ProductSourcingBulkTest.java` (기존 컨트롤러 테스트 스타일 재사용 — `ProductControllerActionLogDetailTest`/`ProductControllerCrawlUploadTest` 패턴): `createBulk`가 id 1,2인 Product 2개를 반환하도록 mock, `POST /products/bulk` 응답 바디가 `[1,2]`인지 검증.
```java
// mock: productCreateUseCase.createBulk(any()) → List.of(productWithId(1L), productWithId(2L))
mockMvc.perform(post("/api/v1/products/bulk").contentType(MediaType.APPLICATION_JSON).content("[]"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$[0]").value(1))
    .andExpect(jsonPath("$[1]").value(2));
```
(Product mock의 getId() 스텁. 컨트롤러 테스트가 plain Mockito면 응답 객체에서 body 리스트 검증.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests '*ProductSourcingBulkTest*'`
Expected: FAIL — 현재 `ResponseEntity<Void>` 반환.

- [ ] **Step 3: 컨트롤러 변경**

`ProductSourcingController.saveProductsBulk`:
```java
	@PostMapping("/products/bulk")
	public ResponseEntity<List<Long>> saveProductsBulk(@RequestBody
	List<ProductSaveRequest> requests) {
		List<com.sbshop.agent.core.domain.product.dto.ProductCreateCommand> commands = requests.stream()
			.map(ProductSaveRequest::toCommand)
			.toList();
		try {
			List<Long> ids = productCreateUseCase.createBulk(commands).stream()
				.map(com.sbshop.agent.core.domain.product.Product::getId)
				.toList();
			actionLogService.record(ActionLogConstants.PRODUCT_BULK_CREATE, null,
				ActionStatus.SUCCESS, "상품 일괄등록 성공 (" + ids.size() + "건)");
			return ResponseEntity.ok(ids);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_BULK_CREATE, null,
				ActionStatus.FAILED, "상품 일괄등록 실패 (" + commands.size() + "건): " + e.getMessage());
			throw e;
		}
	}
```
(반환 타입 `ResponseEntity<List<Long>>`, `List`/`Product` import 확인.)

- [ ] **Step 4: 테스트 통과 + api 컴파일**

Run: `cd backend && ./gradlew :api:test --tests '*ProductSourcingBulkTest*' :api:compileJava`
Expected: PASS / 컴파일 성공.

- [ ] **Step 5: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/controller/ProductSourcingController.java \
        backend/api/src/test/java/com/sbshop/agent/api/controller/ProductSourcingBulkTest.java
git commit -m "feat(SP-D): 상품 일괄등록이 생성 productId 목록 반환 (마켓등록 연결)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: sbCode 이중 채번 버그 수정

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductCreateUseCase.java` (`createBulk` sbCode 블록 ~라인 36-47)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductCreateBulkSbCodeTest.java`

**Interfaces:**
- Consumes: `productReader.getNextSbCodeSequence(String prefix) : String` (다음 가용 전체코드, 예 `"250712IHB006"`).
- Produces: (동작 변경) createBulk가 배치당 getNextSbCodeSequence 1회 호출, 각 항목 sbCode가 시작 seq부터 연속.

- [ ] **Step 1: 재현 테스트 작성**

`ProductCreateBulkSbCodeTest.java`: `productReader.getNextSbCodeSequence(prefix)`를 `prefix+"006"`(즉 기존 max 005) 반환으로 스텁, N=3개 command로 createBulk 실행 시:
- `getNextSbCodeSequence`가 **정확히 1회** 호출(`verify(productReader, times(1))`).
- 생성된 Product들의 sbCode가 `...IHB006, ...IHB007, ...IHB008`로 연속.
```java
// productWriter.saveAll 캡처 또는 반환된 List<Product>의 getSbCode() 검증.
// 기존 ProductCreateUseCase 테스트(있으면)의 mock 준비(imageDownloadClient/imageStorageClient/productReader/productWriter) 재사용.
```
(getNextSbCodeSequence가 여러 번 호출되면 실패하도록 `times(1)` 고정 — 현재 버그는 2번째 항목부터 추가 호출.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductCreateBulkSbCodeTest*'`
Expected: FAIL — 현재 첫 항목이 `IHB001`(반환값 무시), 2번째부터 재호출 → times(1) 위반 + 코드 불연속.

- [ ] **Step 3: 채번 로직 수정**

`ProductCreateUseCase.createBulk`의 seq 초기화 + 루프 내 sbCode 블록을 교체. 루프 진입 전 1회 호출:
```java
		List<Product> products = new ArrayList<>();
		// 배치 시작 시퀀스를 1회만 조회하고 로컬 증가(항목마다 재조회 시 미저장분을 못 봐 충돌·시퀀스 건너뜀 발생).
		String firstCode = productReader.getNextSbCodeSequence(prefix); // 예: prefix+"006"
		int seq = Integer.parseInt(firstCode.substring(prefix.length()));

		for (ProductCreateCommand command : commands) {
			try {
				String sbCode = prefix + String.format("%03d", seq);
				seq++;

				ProductCreateCommand enrichedCommand = enrichWithHostedImages(command);
				Product product = Product.create(sbCode, enrichedCommand);
				products.add(product);
				log.info("상품 생성: sbCode={}, name={}", sbCode, product.getProductName());
			} catch (Exception e) {
				log.error("상품 생성 실패: {}", command.baseName(), e);
			}
		}
```
(기존 `int seq = 0;`와 루프 내 이중 `getNextSbCodeSequence` 블록 제거.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductCreateBulkSbCodeTest*'`
Expected: PASS

- [ ] **Step 5: core 회귀 확인**

Run: `cd backend && ./gradlew :core:test`
Expected: 신규 PASS. 기존 createBulk 테스트가 `IHB001` 시작을 가정하면 새 계약(연속 채번)으로 갱신. pre-existing `SmartStoreOrderFetchFailureTest` 무관.

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductCreateUseCase.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductCreateBulkSbCodeTest.java
git commit -m "fix(SP-D): sbCode 이중 채번 버그 — 배치당 1회 조회 후 로컬 증가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: ProductSaveRequest rawCategory 필드 추가

**Files:**
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/dto/product/ProductSaveRequest.java`
- Test: `backend/api/src/test/java/com/sbshop/agent/api/dto/product/ProductSaveRequestTest.java`

**Interfaces:**
- Produces: `ProductSaveRequest` record에 `String rawCategory` 필드; `toCommand()`가 rawCategory를 command에 반영(현재 null 고정).

> 참고: `origin`, `weight`는 이미 ProductSaveRequest 필드로 존재(프론트가 안 보낼 뿐). `vendor`는 이미 `VendorType`, `marginRate`는 이미 `BigDecimal`. 백엔드 request 갭은 rawCategory 하나.

- [ ] **Step 1: 실패 테스트 작성**

`ProductSaveRequestTest.java`:
```java
package com.sbshop.agent.api.dto.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSaveRequestTest {

	@Test
	@DisplayName("toCommand가 rawCategory를 command에 반영한다")
	void toCommandMapsRawCategory() {
		ProductSaveRequest req = new ProductSaveRequest(
			"https://iherb.com/x", new BigDecimal("10.5"), "베이스명", "Original", "브랜드",
			"미국", new BigDecimal("0.3"), new BigDecimal("500"), null,
			List.of("u0"), "<html>", "건강기능식품/비타민", true, 1,
			new BigDecimal("20"), VendorType.IHB);
		ProductCreateCommand cmd = req.toCommand();
		assertThat(cmd.rawCategory()).isEqualTo("건강기능식품/비타민");
	}
}
```
(생성자 인자 순서는 현 record 필드 순서 + 새 rawCategory 위치에 맞춰 실제 코드에서 조정 — Step 3에서 확정.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests '*ProductSaveRequestTest*'`
Expected: FAIL(컴파일) — record에 rawCategory 없음.

- [ ] **Step 3: record + toCommand 변경**

`ProductSaveRequest.java` — `rawSourceHtml` 다음(또는 논리 위치)에 `String rawCategory` 추가하고 toCommand에서 매핑:
```java
public record ProductSaveRequest(
	String sourceUrl,
	BigDecimal costPrice,
	String baseName,
	String originalName,
	String brand,
	String origin,
	BigDecimal weight,
	BigDecimal capacity,
	MeasureUnit measureUnit,
	List<String> sourceImages,
	String rawSourceHtml,
	String rawCategory,
	boolean isAvailable,
	Integer bundleQuantity,
	BigDecimal marginRate,
	VendorType vendor) {

	public ProductCreateCommand toCommand() {
		return new ProductCreateCommand(
			sourceUrl, costPrice, baseName, originalName, brand, origin,
			weight, capacity, measureUnit, sourceImages, null, rawSourceHtml,
			rawCategory, isAvailable, bundleQuantity, marginRate, vendor);
	}
}
```
(ProductCreateCommand 인자 순서: `..., sourceImages, hostedImages(null), rawSourceHtml, rawCategory, isAvailable, ...` — 기존과 동일하되 13번째 rawCategory를 null→`rawCategory`로.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :api:test --tests '*ProductSaveRequestTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/dto/product/ProductSaveRequest.java \
        backend/api/src/test/java/com/sbshop/agent/api/dto/product/ProductSaveRequestTest.java
git commit -m "feat(SP-D): ProductSaveRequest에 rawCategory 추가 — 보정 카테고리 전송

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 프론트 위저드 재구성 (antd Steps)

**Files:**
- Modify: `frontend/src/api/sourcingApi.ts` (saveProductsBulk 반환 타입, SourcingResult 필드)
- Modify: `frontend/src/pages/ProductRegisterPage.tsx` (전면 재구성)

**Interfaces:**
- Consumes: `sourcingApi.sourceFromIherb(urls)` (기존), `sourcingApi.saveProductsBulk(products)` → `number[]`(Task 1), `sourcingApi.publishToMarket(id, marketType)` (기존 정의), 백엔드 `ProductSaveRequest` 필드(rawCategory 포함, Task 3).

- [ ] **Step 1: sourcingApi 타입 정리**

`sourcingApi.ts` — `SourcingResult`에 크롤이 주는 필드 유지(sourceUrl, baseName, originalName, brand, costPrice, sourceImages, isAvailable, capacity, unit). `saveProductsBulk` 반환은 axios라 호출부에서 `res.data as number[]`로 사용하므로 함수 시그니처는 그대로 두되 주석으로 반환 형태 명시:
```ts
  // POST /api/v1/products/bulk → 생성된 productId 목록(number[])
  saveProductsBulk: (products: Record<string, unknown>[]) =>
    apiClient.post('/api/v1/products/bulk', products),
```
(publishToMarket는 이미 정의됨 — 변경 없음.)

- [ ] **Step 2: ProductRegisterPage 위저드 전면 재구성**

`ProductRegisterPage.tsx` 전체 교체:
```tsx
import { useState } from 'react';
import { Input, Button, Table, Space, message, Typography, Steps, InputNumber, Select, Result, Tag } from 'antd';
import { sourcingApi, type SourcingResult } from '../api/sourcingApi';

const { TextArea } = Input;
const { Title } = Typography;

const VENDOR_OPTIONS = ['IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];
const MARKETS = ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24'];

// 크롤 결과 + 보정 입력을 합친 편집 행
interface EditableRow extends SourcingResult {
  origin?: string;
  weight?: number;
  rawCategory?: string;
  bundleQuantity: number;
  marginRate: number;
  vendor: string;
}

interface PublishOutcome { productId: number; market: string; ok: boolean; error?: string; }

const ProductRegisterPage = () => {
  const [current, setCurrent] = useState(0);
  const [urls, setUrls] = useState('');
  const [rows, setRows] = useState<EditableRow[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [loading, setLoading] = useState(false);
  const [savedIds, setSavedIds] = useState<number[]>([]);
  const [selectedMarkets, setSelectedMarkets] = useState<string[]>([]);
  const [outcomes, setOutcomes] = useState<PublishOutcome[]>([]);

  // Step 1: 크롤
  const handleCrawl = async () => {
    const urlList = urls.split('\n').map((u) => u.trim()).filter(Boolean);
    if (urlList.length === 0) { message.warning('URL을 입력하세요'); return; }
    setLoading(true);
    try {
      const res = await sourcingApi.sourceFromIherb(urlList);
      const scraped = (res.data as SourcingResult[]) || [];
      setRows(scraped.map((s) => ({ ...s, bundleQuantity: 1, marginRate: 20, vendor: 'IHB' })));
      setSelectedRowKeys(scraped.map((_, i) => i));
      message.success(`${scraped.length}개 상품 크롤링 완료`);
      if (scraped.length > 0) setCurrent(1);
    } catch { message.error('크롤링 실패'); }
    finally { setLoading(false); }
  };

  const updateRow = (index: number, patch: Partial<EditableRow>) => {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  };

  // Step 2 → 저장
  const handleSave = async () => {
    const selected = rows.filter((_, i) => selectedRowKeys.includes(i));
    if (selected.length === 0) { message.warning('저장할 상품을 선택하세요'); return; }
    setLoading(true);
    try {
      const res = await sourcingApi.saveProductsBulk(
        selected.map((s) => ({
          sourceUrl: s.sourceUrl, baseName: s.baseName, originalName: s.originalName,
          brand: s.brand, costPrice: s.costPrice, origin: s.origin ?? null,
          weight: s.weight ?? null, capacity: s.capacity, measureUnit: null,
          sourceImages: s.sourceImages, rawSourceHtml: null, rawCategory: s.rawCategory ?? null,
          isAvailable: s.isAvailable, bundleQuantity: s.bundleQuantity,
          marginRate: s.marginRate, vendor: s.vendor,
        }))
      );
      const ids = (res.data as number[]) || [];
      setSavedIds(ids);
      message.success(`${ids.length}개 상품 저장 완료`);
      setCurrent(2);
    } catch { message.error('저장 실패'); }
    finally { setLoading(false); }
  };

  // Step 3: 마켓 등록 — 저장된 productId × 선택 마켓 루프(단건 publish)
  const handlePublish = async () => {
    if (selectedMarkets.length === 0) { message.warning('등록할 마켓을 선택하세요'); return; }
    setLoading(true);
    const results: PublishOutcome[] = [];
    for (const id of savedIds) {
      for (const market of selectedMarkets) {
        try {
          await sourcingApi.publishToMarket(id, market);
          results.push({ productId: id, market, ok: true });
        } catch (e) {
          results.push({ productId: id, market, ok: false, error: (e as Error).message });
        }
      }
    }
    setOutcomes(results);
    setLoading(false);
    const failed = results.filter((r) => !r.ok).length;
    if (failed === 0) message.success('모든 마켓 등록 완료');
    else message.warning(`${failed}개 조합 등록 실패 — 결과를 확인하세요`);
    setCurrent(3);
  };

  const reset = () => {
    setCurrent(0); setUrls(''); setRows([]); setSelectedRowKeys([]);
    setSavedIds([]); setSelectedMarkets([]); setOutcomes([]);
  };

  const editColumns = [
    { title: '브랜드', dataIndex: 'brand', width: 90, ellipsis: true },
    { title: '상품명', dataIndex: 'baseName', ellipsis: true },
    { title: '원가($)', dataIndex: 'costPrice', width: 80 },
    { title: '원산지', width: 110, render: (_: unknown, r: EditableRow, i: number) => (
      <Input size="small" value={r.origin} onChange={(e) => updateRow(i, { origin: e.target.value })} />) },
    { title: '중량', width: 90, render: (_: unknown, r: EditableRow, i: number) => (
      <InputNumber size="small" value={r.weight} onChange={(v) => updateRow(i, { weight: v ?? undefined })} />) },
    { title: '카테고리', width: 130, render: (_: unknown, r: EditableRow, i: number) => (
      <Input size="small" value={r.rawCategory} onChange={(e) => updateRow(i, { rawCategory: e.target.value })} />) },
    { title: '묶음', width: 70, render: (_: unknown, r: EditableRow, i: number) => (
      <InputNumber size="small" min={1} value={r.bundleQuantity} onChange={(v) => updateRow(i, { bundleQuantity: v ?? 1 })} />) },
    { title: '마진율(%)', width: 90, render: (_: unknown, r: EditableRow, i: number) => (
      <InputNumber size="small" min={0} value={r.marginRate} onChange={(v) => updateRow(i, { marginRate: v ?? 0 })} />) },
    { title: '공급처', width: 100, render: (_: unknown, r: EditableRow, i: number) => (
      <Select size="small" style={{ width: 90 }} value={r.vendor} options={VENDOR_OPTIONS.map((v) => ({ value: v, label: v }))}
        onChange={(v) => updateRow(i, { vendor: v })} />) },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>신규 상품 등록 (iHerb)</Title>
      <Steps current={current} style={{ marginBottom: 24 }} items={[
        { title: '크롤링' }, { title: '보정·가격' }, { title: '마켓 등록' }, { title: '완료' },
      ]} />

      {current === 0 && (
        <Space direction="vertical" style={{ width: '100%' }}>
          <TextArea rows={5} placeholder="iHerb 상품 URL을 한 줄에 하나씩 입력하세요"
            value={urls} onChange={(e) => setUrls(e.target.value)} />
          <Button type="primary" loading={loading} onClick={handleCrawl}>크롤링</Button>
        </Space>
      )}

      {current === 1 && (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Table<EditableRow> rowKey={(_, i) => i ?? 0} columns={editColumns} dataSource={rows}
            rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
            pagination={false} size="small" scroll={{ y: 460 }} />
          <Space>
            <Button onClick={() => setCurrent(0)}>이전</Button>
            <Button type="primary" loading={loading} onClick={handleSave}>
              선택한 상품 저장 ({selectedRowKeys.length}개)
            </Button>
          </Space>
        </Space>
      )}

      {current === 2 && (
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>저장된 상품 {savedIds.length}개. 등록할 마켓을 선택하세요.</div>
          <Select mode="multiple" style={{ width: 400 }} placeholder="마켓 선택"
            value={selectedMarkets} onChange={setSelectedMarkets}
            options={MARKETS.map((m) => ({ value: m, label: m }))} />
          <Space>
            <Button type="primary" loading={loading} onClick={handlePublish}>
              마켓 등록 ({savedIds.length}상품 × {selectedMarkets.length}마켓)
            </Button>
            <Button onClick={reset}>마켓 등록 건너뛰고 종료</Button>
          </Space>
        </Space>
      )}

      {current === 3 && (
        <Result status={outcomes.every((o) => o.ok) ? 'success' : 'warning'}
          title="마켓 등록 결과"
          subTitle={`성공 ${outcomes.filter((o) => o.ok).length} / 실패 ${outcomes.filter((o) => !o.ok).length}`}
          extra={[
            <Space key="list" direction="vertical" style={{ textAlign: 'left' }}>
              {outcomes.map((o, i) => (
                <div key={i}>
                  <Tag color={o.ok ? 'green' : 'red'}>{o.ok ? '성공' : '실패'}</Tag>
                  상품 {o.productId} · {o.market}{o.error ? ` — ${o.error}` : ''}
                </div>
              ))}
            </Space>,
            <Button key="new" type="primary" onClick={reset}>새 등록</Button>,
          ]} />
      )}
    </div>
  );
};

export default ProductRegisterPage;
```

- [ ] **Step 3: 타입체크 + 빌드**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: tsc 0, build 성공. (SourcingResult에 `unit` 필드 등 미사용 경고 없어야. antd Steps/Result/Tag import 확인.)

- [ ] **Step 4: 수동 스모크(선택)**

`cd frontend && npm run dev` → 신규등록 페이지: 크롤 → 보정 테이블(원산지/중량/카테고리/마진/공급처 입력) → 저장 → 마켓 다중선택 → 등록 → 결과 표면화 흐름 확인.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/ProductRegisterPage.tsx frontend/src/api/sourcingApi.ts
git commit -m "feat(SP-D): 신규등록 위저드 완결 — 크롤→보정→가격→마켓등록 (antd Steps)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 통합 게이트 + 라이브 확인 준비

**Files:** 없음(검증 전용).

- [ ] **Step 1: 백엔드 전체 게이트**

Run: `cd backend && ./gradlew :core:test :api:test --tests '*ProductSourcing*' --tests '*ProductCreateBulk*' --tests '*ProductSaveRequest*' :api:compileJava`
Expected: SP-D 신규/변경 테스트 PASS. pre-existing 무관 실패(core `SmartStoreOrderFetchFailureTest`, infra `ImageDownloadServiceCharacterizationTest` SIGABRT)는 `git diff --name-only <base>..HEAD`로 diff 밖임을 확인해 기록.

- [ ] **Step 2: 프론트 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 0 / 성공.

- [ ] **Step 3: 라이브 확인 체크리스트 문서화**

배포 후(사용자 허가): iHerb URL 크롤 → 보정(원산지·중량·카테고리·마진·공급처) → 저장(productId 반환) → 마켓 다중선택 등록 → 마켓별 성공/실패 표면화. sbCode가 기존 상품 뒤로 연속 채번되는지, vendor enum 매핑, 마켓 등록 시 카테고리 요구로 실패하는 마켓(범위 밖 매핑) 확인.

---

## Self-Review 체크

- **Spec 커버리지:** bulk productId 반환(Task 1)·sbCode 버그(Task 2)·rawCategory 필드(Task 3)·프론트 위저드 4단계+마켓등록 루프(Task 4)·게이트(Task 5). FeePolicy/카테고리 매핑 범위 밖 명시. DDL 없음. ✅
- **Placeholder:** 코드/명령/기대출력 구체화. 위저드 전체 코드 제공. vendor는 VendorType 드롭다운(enum 값 배열)으로 확정(suppliers 미배선 — 근거: command에 supplier 필드 없음). ✅
- **타입 일관성:** `createBulk : List<Product>`(기존) → 컨트롤러가 id 추출(Task 1). `saveProductsBulk` 응답 `number[]` ↔ 프론트 `res.data as number[]`(Task 4). `publishToMarket(id, marketType)`(기존) ↔ 프론트 루프(Task 4). `ProductSaveRequest`에 rawCategory 추가(Task 3) ↔ 프론트가 rawCategory 전송(Task 4). `getNextSbCodeSequence`는 다음 가용 전체코드 반환 — Task 2에서 1회 호출·parseInt. ✅
- **미검증 라이브 주의:** vendor enum, publish 카테고리 요구(범위 밖 매핑), 이미지 자동 반영은 Task 5 라이브 확인에 명시.
