package com.sbshop.agent.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * api 모듈 비동기 활성화 설정.
 *
 * <p>{@code productBatchExecutor} 빈은 core {@code AsyncConfig}로 이전됨(D-011): 소비자
 * {@code BatchPriceStockService}가 core에 있고 호출자가 api와 worker 양쪽이라, 빈이 core에 있어야 두
 * 컨텍스트 모두에서 한정자가 해소된다. 이 클래스는 현재 빈이 없는 껍데기이나, D-009에서 부여한 빈 이름
 * {@code apiAsyncConfig}와 그 회귀 테스트를 보존하기 위해 남겨둔다(정리 후보 → 원장 참조).
 */
@Configuration("apiAsyncConfig")
@EnableAsync
public class AsyncConfig {}
