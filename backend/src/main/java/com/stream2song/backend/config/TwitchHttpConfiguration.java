package com.stream2song.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class TwitchHttpConfiguration {

    @Bean
    RestTemplate twitchRestTemplate() {
        return new RestTemplate();
    }
}
