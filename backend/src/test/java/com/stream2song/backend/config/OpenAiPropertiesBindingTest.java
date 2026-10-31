package com.stream2song.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiPropertiesBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsConfiguredApiKeyAndModelInSpringContext() {
        contextRunner.withPropertyValues("openai.api-key=dummy-test-key", "openai.model=test-model")
                .run(context -> {
                    OpenAiProperties properties = context.getBean(OpenAiProperties.class);
                    assertThat(properties.apiKey()).isEqualTo("dummy-test-key");
                    assertThat(properties.model()).isEqualTo("test-model");
                    assertThat(properties.isConfigured()).isTrue();
                });
    }

    @Test
    void usesDefaultModelAndTreatsMissingOrBlankKeyAsUnconfigured() {
        contextRunner.run(context -> {
            OpenAiProperties properties = context.getBean(OpenAiProperties.class);
            assertThat(properties.model()).isEqualTo("gpt-6-luna");
            assertThat(properties.isConfigured()).isFalse();
        });
        contextRunner.withPropertyValues("openai.api-key= ").run(context ->
                assertThat(context.getBean(OpenAiProperties.class).isConfigured()).isFalse());
    }

    @Test
    void redactsConfiguredKeyFromStringRepresentation() {
        contextRunner.withPropertyValues("openai.api-key=dummy-redacted-fixture", "openai.model=test-model")
                .run(context -> {
                    String rendered = context.getBean(OpenAiProperties.class).toString();
                    assertThat(rendered).contains("redacted").doesNotContain("dummy-redacted-fixture");
                });
    }

    @Test
    void spokenPreviewModelAndVoiceBindSeparatelyWithSafeDefaults() {
        contextRunner.run(context -> {
            OpenAiSpokenProperties defaults = context.getBean(OpenAiSpokenProperties.class);
            assertThat(defaults.model()).isEqualTo("gpt-realtime-2.1-mini");
            assertThat(defaults.voice()).isEqualTo("marin");
        });
        contextRunner.withPropertyValues("openai.spoken.model=custom-realtime", "openai.spoken.voice=cedar")
                .run(context -> {
                    OpenAiSpokenProperties configured = context.getBean(OpenAiSpokenProperties.class);
                    assertThat(configured.model()).isEqualTo("custom-realtime");
                    assertThat(configured.voice()).isEqualTo("cedar");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OpenAiProperties.class, OpenAiSpokenProperties.class})
    static class PropertiesConfiguration { }
}
