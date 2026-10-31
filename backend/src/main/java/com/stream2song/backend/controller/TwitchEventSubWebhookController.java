package com.stream2song.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stream2song.backend.service.TwitchEventSubWebhookException;
import com.stream2song.backend.service.TwitchEventSubWebhookService;

@RestController
@RequestMapping("/api/twitch/eventsub")
public class TwitchEventSubWebhookController {
    private static final Logger log = LoggerFactory.getLogger(TwitchEventSubWebhookController.class);
    private final TwitchEventSubWebhookService webhookService;

    public TwitchEventSubWebhookController(TwitchEventSubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(path = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(
            @RequestHeader(name = "Twitch-Eventsub-Message-Id", required = false) String messageId,
            @RequestHeader(name = "Twitch-Eventsub-Message-Timestamp", required = false) String messageTimestamp,
            @RequestHeader(name = "Twitch-Eventsub-Message-Signature", required = false) String signature,
            @RequestHeader(name = "Twitch-Eventsub-Message-Type", required = false) String messageType,
            @RequestBody(required = false) byte[] rawBody) {
        try {
            String challenge = webhookService.handle(messageId, messageTimestamp, signature, messageType, rawBody);
            if (challenge != null) {
                return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
            }
            return ResponseEntity.noContent().build();
        } catch (TwitchEventSubWebhookException exception) {
            log.warn("Rejected Twitch EventSub webhook: reason={} httpStatus={}", exception.reason(),
                    exception.status().value());
            return ResponseEntity.status(exception.status()).build();
        }
    }
}
