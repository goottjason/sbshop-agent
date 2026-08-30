package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangSteppedPriceChangeTest {

	@Mock
	private CoupangProperties properties;
	@Mock
	private CoupangRestClient restClient;
	@Mock
	private CoupangCategoryPredictor categoryPredictor;
	@Mock
	private CoupangProductParser productParser;
	@Mock
	private CoupangSearchTagGenerator searchTagGenerator;
	@Mock
	private CoupangDataMapper dataMapper;
	@Mock
	private CoupangMetaService metaService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private CoupangMarketClient client;
	private final List<Integer> pricePuts = new ArrayList<>();

	private static final String ITEM = "999";
	private static final Pattern PRICE_PATH = Pattern.compile("/vendor-items/" + ITEM + "/prices/(\\d+)");

	/** 쿠팡을 흉내낸다 — 현재가 대비 -50%/+100% 를 벗어나면 거부하고, 통과하면 현재가가 바뀐다. */
	private void simulateCoupang(int startingPrice) {
		int[] current = {startingPrice};
		lenient().when(restClient.get(anyString()))
			.thenAnswer(inv -> "{\"data\":{\"salePrice\":" + current[0] + "}}");
		lenient().when(restClient.put(anyString(), anyMap())).thenAnswer(inv -> {
			String path = inv.getArgument(0);
			Matcher m = PRICE_PATH.matcher(path);
			if (!m.find()) {
				return "{\"code\":\"SUCCESS\"}";
			}
			int wanted = Integer.parseInt(m.group(1));
			if (wanted < current[0] / 2 || wanted > current[0] * 2) {
				throw new IllegalStateException("400 Bad Request: 가격변경에 실패했습니다."
					+ " [옵션ID[" + ITEM + "] : 판매가 변경이 불가능합니다."
					+ " 변경전 판매가의 최대 50% 인하/최대 100%인상까지 변경가능합니다.]");
			}
			current[0] = wanted;
			pricePuts.add(wanted);
			return "{\"code\":\"SUCCESS\"}";
		});
	}

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
		pricePuts.clear();
	}

	@Test
	@DisplayName("한도 안이면 한 번에 보낸다 — 멀쩡한 경우를 복잡하게 만들지 않는다")
	void withinLimitSendsOnce() {
		simulateCoupang(10000);

		client.syncPriceAndStock(ITEM, Map.of("salePrice", 10000), 14000, 10, false);

		assertThat(pricePuts).containsExactly(14000);
	}

	@Test
	@DisplayName("100% 넘는 인상은 두 배씩 나눠 올린다 — 10,000 → 90,000")
	void largeIncreaseIsStepped() {
		simulateCoupang(10000);

		client.syncPriceAndStock(ITEM, Map.of("salePrice", 10000), 90000, 10, false);

		assertThat(pricePuts).hasSizeGreaterThan(1);
		assertThat(pricePuts.get(pricePuts.size() - 1)).isEqualTo(90000);
		assertThat(pricePuts).isSorted();
	}

	@Test
	@DisplayName("50% 넘는 인하는 절반씩 나눠 내린다 — 80,000 → 15,000")
	void largeDecreaseIsStepped() {
		simulateCoupang(80000);

		client.syncPriceAndStock(ITEM, Map.of("salePrice", 80000), 15000, 10, false);

		assertThat(pricePuts).hasSizeGreaterThan(1);
		assertThat(pricePuts.get(pricePuts.size() - 1)).isEqualTo(15000);
	}

	@Test
	@DisplayName("저장된 rawData 가 비어도 마켓에서 현재가를 읽어 단계로 올린다 — 1,242건이 이 경우다")
	void readsCurrentPriceFromMarketWhenRawDataEmpty() {
		simulateCoupang(10000);

		client.syncPriceAndStock(ITEM, Map.of(), 90000, 10, false);

		assertThat(pricePuts).hasSizeGreaterThan(1);
		assertThat(pricePuts.get(pricePuts.size() - 1)).isEqualTo(90000);
	}

	@Test
	@DisplayName("현재가를 끝내 못 읽으면 한 번만 시도한다 — 추측으로 여러 번 밀어넣지 않는다")
	void withoutCurrentPriceTriesOnce() {
		lenient().when(restClient.get(anyString())).thenReturn("{}");
		lenient().when(restClient.put(anyString(), anyMap())).thenAnswer(inv -> {
			String path = inv.getArgument(0);
			Matcher m = PRICE_PATH.matcher(path);
			if (m.find()) {
				pricePuts.add(Integer.parseInt(m.group(1)));
			}
			return "{\"code\":\"SUCCESS\"}";
		});

		client.syncPriceAndStock(ITEM, Map.of(), 90000, 10, false);

		assertThat(pricePuts).containsExactly(90000);
	}
}
