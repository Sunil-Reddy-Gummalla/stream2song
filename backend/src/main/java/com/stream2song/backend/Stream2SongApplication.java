package com.stream2song.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.config.TwitchEventSubProperties;

@SpringBootApplication
@EnableConfigurationProperties({TwitchOAuthProperties.class, TwitchEventSubProperties.class})
public class Stream2SongApplication {

    public static void main(String[] args) {
        SpringApplication.run(Stream2SongApplication.class, args);
    }
}
