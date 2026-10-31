package com.stream2song.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.stream2song.backend.client.JavaOpenAiRealtimeWebSocketTransport;

import static org.assertj.core.api.Assertions.assertThat;

class SpokenLyricsSpringWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SpokenPreviewConfiguration.class);

    @Test void productionServicesHaveResolvableSpringConstructorsWithoutProviderCalls() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SpokenLyricsService.class);
            assertThat(context).hasSingleBean(JavaOpenAiRealtimeWebSocketTransport.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SpokenLyricsService.class, JavaOpenAiRealtimeWebSocketTransport.class})
    static class SpokenPreviewConfiguration {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean MomentAnalysisSnapshotService snapshots() { return new MomentAnalysisSnapshotService(); }
        @Bean TwitchBroadcasterProvider broadcaster() { return () -> null; }
        @Bean SpokenLyricsProvider provider() {
            return new SpokenLyricsProvider() {
                @Override public boolean isConfigured() { return false; }
                @Override public String fingerprint() { return "fixture"; }
                @Override public byte[] synthesize(String lyrics) { throw new AssertionError("provider call not expected"); }
            };
        }
    }
}
