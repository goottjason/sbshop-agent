package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ScraplingDiscoveryTimeoutTest {
    private final ScraplingIherbClient client = new ScraplingIherbClient(new ObjectMapper(), "http://localhost:1");

    @Test
    void defaultTwelvePageCrawlHasTimeForNavigationAndSelectorWaits() {
        Duration timeout = ReflectionTestUtils.invokeMethod(client, "discoverTimeout", 4, 3);
        assertThat(timeout).isGreaterThanOrEqualTo(Duration.ofSeconds(12 * 90));
    }

    @Test
    void largeCrawlStillHasAnUpperBound() {
        Duration timeout = ReflectionTestUtils.invokeMethod(client, "discoverTimeout", 100, 100);
        assertThat(timeout).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void singlePageHasTimeToStartBrowser() {
        Duration timeout = ReflectionTestUtils.invokeMethod(client, "discoverTimeout", 1, 1);
        assertThat(timeout).isEqualTo(Duration.ofMinutes(3));
    }
}
