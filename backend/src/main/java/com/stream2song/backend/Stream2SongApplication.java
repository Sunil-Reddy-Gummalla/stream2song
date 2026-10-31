package com.stream2song.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.config.DeepgramProperties;
import com.stream2song.backend.config.TranscriptionCacheProperties;
import com.stream2song.backend.config.OpenAiProperties;
import com.stream2song.backend.config.MusicProperties;
import com.stream2song.backend.config.OpenAiSpokenProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({TwitchOAuthProperties.class, TwitchEventSubProperties.class, DeepgramProperties.class,
        TranscriptionCacheProperties.class, OpenAiProperties.class, MusicProperties.class, OpenAiSpokenProperties.class})
public class Stream2SongApplication {
    private static final Logger log = LoggerFactory.getLogger(Stream2SongApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(Stream2SongApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logApplicationReady() {
        log.info("Stream2Song backend started and ready");
    }
}
