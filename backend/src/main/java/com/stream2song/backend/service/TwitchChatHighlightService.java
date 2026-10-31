package com.stream2song.backend.service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stream2song.backend.dto.TwitchChatHighlightsResponse;
import com.stream2song.backend.dto.TwitchChatHighlightsResponse.Highlight;
import com.stream2song.backend.model.StreamSessionState;
import com.stream2song.backend.model.TwitchChatMessage;

/** Deterministic chat activity heuristics; output is not confirmation of a stream event. */
@Service
public class TwitchChatHighlightService {
    private static final Logger log = LoggerFactory.getLogger(TwitchChatHighlightService.class);
    private static final int MIN_MESSAGES_PER_WINDOW = 3;
    private static final int MAX_EVIDENCE_MESSAGES = 3;
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "about", "after", "again", "also", "and", "are", "because", "been", "being", "but", "can",
            "cant", "come", "could", "did", "does", "doing", "dont", "down", "even", "from", "get",
            "getting", "going", "got", "had", "has", "have", "her", "here", "him", "his", "how", "into",
            "just", "like", "lolol", "look", "make", "more", "most", "much", "need", "not", "now", "only",
            "our", "out", "over", "really", "right", "same", "see", "she", "some", "still", "that", "the",
            "their", "them", "then", "there", "they", "this", "those", "through", "too", "very", "was",
            "well", "were", "what", "when", "where", "which", "while", "with", "would", "yeah", "your");
    private static final Set<String> REACTIONS = Set.of(
            "ah", "bruh", "clap", "ez", "gg", "haha", "hype", "kappa", "kekw", "letsgo", "lmao", "monkas",
            "no", "omg", "pepega", "pog", "poggers", "rip", "sadge", "wow", "wtf");
    private static final Set<String> FILLER = Set.of("aaa", "aaaa", "ahhh", "hahaha", "heyyy", "hmmmm", "oooh", "yeee");

    private final TwitchBroadcasterProvider broadcasterProvider;
    private final TwitchStreamSessionService sessionService;
    private final TwitchChatMessageService messageService;

    public TwitchChatHighlightService(TwitchBroadcasterProvider broadcasterProvider,
            TwitchStreamSessionService sessionService, TwitchChatMessageService messageService) {
        this.broadcasterProvider = broadcasterProvider;
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    public TwitchChatHighlightsResponse currentStreamHighlights(int windowSeconds, int limit) {
        String broadcasterId = broadcasterProvider.connectedBroadcasterId();
        StreamSessionState state = sessionService.currentFor(broadcasterId);
        String streamId = state == null ? null : state.twitchStreamId();
        List<TwitchChatMessage> messages = messageService.allForStream(broadcasterId, streamId);
        return analyze(messages, streamId, windowSeconds, limit);
    }

    TwitchChatHighlightsResponse analyze(List<TwitchChatMessage> source, String streamId,
            int windowSeconds, int limit) {
        return analyze(source, streamId, windowSeconds, limit, null);
    }

    public TwitchChatHighlightsResponse analyzeClipRange(List<TwitchChatMessage> source, String streamId,
            Instant clipStartUtc, Instant clipEndUtc, int limit) {
        if (clipStartUtc == null || clipEndUtc == null || !clipStartUtc.isBefore(clipEndUtc)) {
            return response(streamId, 10, 0, "Clip chat range is unavailable.", List.of());
        }
        List<TwitchChatMessage> inClip = source == null ? List.of() : source.stream()
                .filter(message -> message != null && message.timestamp() != null)
                .filter(message -> !message.timestamp().isBefore(clipStartUtc) && message.timestamp().isBefore(clipEndUtc))
                .toList();
        return analyze(inClip, streamId, 10, limit, clipStartUtc);
    }

    private TwitchChatHighlightsResponse analyze(List<TwitchChatMessage> source, String streamId,
            int windowSeconds, int limit, Instant anchor) {
        log.info("Starting Twitch chat highlight analysis: streamId={} windowSeconds={}", streamId, windowSeconds);
        try {
            List<TwitchChatMessage> messages = uniqueTimestampedMessages(source);
            if (messages.isEmpty()) {
                log.info("Completed Twitch chat highlight analysis: streamId={} messagesAnalyzed=0 highlightsFound=0",
                        streamId);
                return response(streamId, windowSeconds, 0,
                        "No timestamped chat messages are available for this stream.", List.of());
            }

            Map<Long, List<TwitchChatMessage>> windows = groupByWindow(messages, windowSeconds, anchor);
            Set<String> streamUsernames = new HashSet<>();
            messages.stream().map(TwitchChatMessage::username).filter(name -> name != null)
                    .map(this::normalizeToken).filter(name -> !name.isBlank()).forEach(streamUsernames::add);
            int maximumMessageCount = windows.values().stream().mapToInt(List::size).max().orElse(0);
            List<Candidate> candidates = windows.entrySet().stream()
                    .filter(entry -> entry.getValue().size() >= MIN_MESSAGES_PER_WINDOW)
                    .map(entry -> candidate(entry.getKey(), entry.getValue(), windows, maximumMessageCount,
                            windowSeconds, streamUsernames, anchor))
                    .sorted(Comparator.comparingDouble((Candidate item) -> item.score).reversed()
                            .thenComparing(item -> item.start))
                    .toList();

            List<Candidate> selected = selectDistinct(candidates, windowSeconds, limit);
            String message = selected.isEmpty()
                    ? "Not enough chat activity in any time window to suggest a highlight yet."
                    : null;
            List<Highlight> highlights = selected.stream().map(Candidate::toResponse).toList();
            log.info("Completed Twitch chat highlight analysis: streamId={} messagesAnalyzed={} highlightsFound={}",
                    streamId, messages.size(), highlights.size());
            log.debug("Twitch chat highlight analysis used {} populated windows", windows.size());
            return response(streamId, windowSeconds, messages.size(), message, highlights);
        } catch (RuntimeException exception) {
            log.error("Twitch chat highlight analysis failed: streamId={} failureType={}", streamId,
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private TwitchChatHighlightsResponse response(String streamId, int windowSeconds, int analyzed,
            String message, List<Highlight> highlights) {
        return new TwitchChatHighlightsResponse(streamId, windowSeconds, analyzed, true, message, highlights);
    }

    private List<TwitchChatMessage> uniqueTimestampedMessages(List<TwitchChatMessage> source) {
        Map<String, TwitchChatMessage> unique = new LinkedHashMap<>();
        for (TwitchChatMessage message : source) {
            if (message == null || message.timestamp() == null || message.text() == null || message.text().isBlank())
                continue;
            String key = message.messageId() == null || message.messageId().isBlank()
                    ? message.streamId() + "|" + message.timestamp() + "|" + message.userId() + "|" + message.text()
                    : message.messageId();
            unique.putIfAbsent(key, message);
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(TwitchChatMessage::timestamp).thenComparing(
                        TwitchChatMessage::messageId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private Map<Long, List<TwitchChatMessage>> groupByWindow(List<TwitchChatMessage> messages, int windowSeconds, Instant anchor) {
        Map<Long, List<TwitchChatMessage>> windows = new HashMap<>();
        for (TwitchChatMessage message : messages) {
            long base = anchor == null ? 0 : anchor.getEpochSecond();
            long index = Math.floorDiv(message.timestamp().getEpochSecond() - base, windowSeconds);
            windows.computeIfAbsent(index, ignored -> new ArrayList<>()).add(message);
        }
        return windows;
    }

    private Candidate candidate(long index, List<TwitchChatMessage> messages,
            Map<Long, List<TwitchChatMessage>> windows, int maximumMessageCount, int windowSeconds,
            Set<String> streamUsernames, Instant anchor) {
        Map<String, Integer> reactions = new HashMap<>();
        Map<String, Integer> terms = new HashMap<>();
        for (TwitchChatMessage message : messages) {
            Set<String> tokensInMessage = new HashSet<>(tokens(message.text()));
            for (String token : tokensInMessage) {
                if (REACTIONS.contains(token)) reactions.merge(token, 1, Integer::sum);
                else if (!streamUsernames.contains(token) && isMeaningful(token)) terms.merge(token, 1, Integer::sum);
            }
        }
        Map<String, Integer> repeatedReactions = repeated(reactions);
        Map<String, Integer> repeatedTerms = repeated(terms);
        double adjacentAverage = adjacentAverage(index, windows);
        double activity = Math.min(1.0, messages.size() / (double) Math.max(3, maximumMessageCount));
        boolean hasSurroundingData = windows.size() > 1;
        double burst = hasSurroundingData
                ? messages.size() / (messages.size() + adjacentAverage)
                : 0.0;
        double reactionStrength = Math.min(1.0, total(repeatedReactions) / (double) messages.size());
        double termStrength = Math.min(1.0, total(repeatedTerms) / (double) messages.size());
        double score = roundOneDecimal(100.0 * (0.40 * activity + 0.25 * burst
                + 0.23 * reactionStrength + 0.12 * termStrength));

        Instant start = anchor == null ? Instant.ofEpochSecond(index * (long) windowSeconds)
                : anchor.plusSeconds(index * (long) windowSeconds);
        Instant end = start.plusSeconds(windowSeconds);
        return new Candidate(start, end, score, messages, repeatedReactions, repeatedTerms,
                adjacentAverage, hasSurroundingData, windowSeconds);
    }

    private double adjacentAverage(long index, Map<Long, List<TwitchChatMessage>> windows) {
        int count = 0;
        if (windows.containsKey(index - 1)) count += windows.get(index - 1).size();
        if (windows.containsKey(index + 1)) count += windows.get(index + 1).size();
        return count / 2.0;
    }

    private List<Candidate> selectDistinct(List<Candidate> ranked, int windowSeconds, int limit) {
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : ranked) {
            boolean duplicateAdjacentMoment = selected.stream().anyMatch(existing ->
                    Math.abs(existing.start.getEpochSecond() - candidate.start.getEpochSecond()) <= windowSeconds
                            && sharesRepeatedTerms(existing, candidate));
            if (!duplicateAdjacentMoment) selected.add(candidate);
            if (selected.size() == limit) break;
        }
        return selected;
    }

    private boolean sharesRepeatedTerms(Candidate first, Candidate second) {
        return first.repeatedReactions.keySet().stream().anyMatch(second.repeatedReactions::containsKey)
                || first.repeatedTerms.keySet().stream().anyMatch(second.repeatedTerms::containsKey);
    }

    private Map<String, Integer> repeated(Map<String, Integer> counts) {
        Map<String, Integer> result = new HashMap<>();
        counts.forEach((token, count) -> {
            if (count >= 2) result.put(token, count);
        });
        return result;
    }

    private int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private List<String> tokens(String text) {
        String withoutUrls = URL.matcher(text).replaceAll(" ");
        String normalized = Normalizer.normalize(withoutUrls, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String raw : NON_WORD.split(normalized)) {
            if (raw.isBlank()) continue;
            String token = normalizeToken(raw);
            if (token.length() > 3 && token.chars().distinct().count() == 1) continue;
            if (FILLER.contains(token)) continue;
            result.add(token);
        }
        return result;
    }

    private String normalizeToken(String token) {
        String normalized = Normalizer.normalize(token, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(normalized).replaceAll("");
    }

    private boolean isMeaningful(String token) {
        return token.length() >= 4 && !STOPWORDS.contains(token) && !FILLER.contains(token)
                && !token.matches("\\d+") && !token.contains("www") && !token.contains("http");
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private final class Candidate {
        private final Instant start;
        private final Instant end;
        private final double score;
        private final List<TwitchChatMessage> messages;
        private final Map<String, Integer> repeatedReactions;
        private final Map<String, Integer> repeatedTerms;
        private final double adjacentAverage;
        private final boolean hasSurroundingData;
        private final int windowSeconds;

        private Candidate(Instant start, Instant end, double score, List<TwitchChatMessage> messages,
                Map<String, Integer> repeatedReactions, Map<String, Integer> repeatedTerms,
                double adjacentAverage, boolean hasSurroundingData, int windowSeconds) {
            this.start = start;
            this.end = end;
            this.score = score;
            this.messages = messages;
            this.repeatedReactions = repeatedReactions;
            this.repeatedTerms = repeatedTerms;
            this.adjacentAverage = adjacentAverage;
            this.hasSurroundingData = hasSurroundingData;
            this.windowSeconds = windowSeconds;
        }

        private Highlight toResponse() {
            String summary = buildSummary();
            String explanation = buildExplanation();
            List<String> evidence = messages.stream().map(TwitchChatMessage::text).distinct()
                    .limit(MAX_EVIDENCE_MESSAGES).toList();
            return new Highlight(start, end, score, messages.size(), summary, explanation, evidence);
        }

        private String buildSummary() {
            List<String> repeated = strongest(repeatedReactions, 2);
            String kind = "reactions";
            if (repeated.isEmpty()) {
                repeated = strongest(repeatedTerms, 2);
                kind = "terms";
            }
            if (repeated.isEmpty()) return "High chat activity over " + windowSeconds + " seconds.";
            return "Repeated chat " + kind + ": " + String.join(", ", repeated) + ".";
        }

        private String buildExplanation() {
            List<String> details = new ArrayList<>();
            details.add(messages.size() + " messages in " + windowSeconds + " seconds");
            if (adjacentAverage > 0 && messages.size() > adjacentAverage * 1.5) {
                details.add("message volume was " + String.format(Locale.ROOT, "%.1f", messages.size() / adjacentAverage)
                        + "× the average of adjacent windows");
            } else if (hasSurroundingData && adjacentAverage == 0) {
                details.add("adjacent windows had no messages");
            }
            List<String> reactions = strongest(repeatedReactions, 2);
            if (!reactions.isEmpty()) details.add("repeated reactions: " + String.join(", ", reactions));
            List<String> terms = strongest(repeatedTerms, 2);
            if (!terms.isEmpty()) details.add("recurring terms: " + String.join(", ", terms));
            return String.join("; ", details) + ". This is a chat-activity heuristic, not a verified stream event.";
        }

        private List<String> strongest(Map<String, Integer> counts, int limit) {
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                    .limit(limit).map(Map.Entry::getKey).toList();
        }
    }
}
