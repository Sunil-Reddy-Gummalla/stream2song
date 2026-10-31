package com.stream2song.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse.ChatEvidence;
import com.stream2song.backend.dto.MemorableMomentsResponse.Moment;
import com.stream2song.backend.dto.MemorableMomentsResponse.TranscriptEvidence;
import com.stream2song.backend.dto.TwitchChatHighlightsResponse;

/** Deterministic evidence ranking only; scores are not probabilities or semantic judgments. */
@Component
public class MemorableMomentExtractionEngine {
    private static final int MAX_MOMENTS = 10;
    private static final int MAX_TRANSCRIPT_EVIDENCE = 4;
    private static final int MAX_CHAT_MESSAGES = 3;
    private static final Pattern NORMALIZE = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> EXCITED = Set.of("wow", "amazing", "incredible", "insane", "unbelievable",
            "pog", "poggers", "letsgo", "let s go", "clutch", "no way", "oh my god", "what a play", "holy",
            "haha", "hahaha", "shaking", "crying");
    private static final Set<String> NOTABLE = Set.of("important", "first time", "finally", "never", "remember",
            "secret", "actually", "huge", "changed everything", "did it");
    private static final Set<String> CHAT_REACTIONS = Set.of("pog", "poggers", "wow", "omg", "kekw", "lol",
            "lmao", "hype", "gg", "bruh", "no way", "insane", "letsgo", "let s go");
    private static final Set<String> REPETITION_FUNCTION_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "but", "by",
            "for", "from", "go", "i", "in", "is", "it", "let", "lets", "me", "my", "of", "on", "or", "s", "the", "to", "we", "you");
    private static final Set<String> STANDALONE_REACTION_CUES = Set.of("wow", "pog", "poggers", "bruh", "hype", "gg", "lol", "lmao", "omg");

    public List<Moment> extract(String clipId, double durationSeconds, String association,
            String transcriptText, List<ClipTranscriptionResponse.TimedTranscriptSegment> transcriptSegments,
            List<TwitchChatHighlightsResponse.Highlight> chatHighlights, Instant streamStartedAt, Integer vodOffsetSeconds,
            boolean verifiedAssociation) {
        List<Candidate> candidates = transcriptCandidates(durationSeconds, transcriptText, transcriptSegments);
        if (streamStartedAt != null && vodOffsetSeconds != null && chatHighlights != null) {
            candidates.addAll(chatCandidates(streamStartedAt, vodOffsetSeconds, durationSeconds, chatHighlights));
        }
        if (verifiedAssociation) candidates.forEach(candidate -> candidate.contributors.add("metadata_context"));
        List<Candidate> merged = mergeCandidates(candidates);
        merged.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.start == null ? Double.POSITIVE_INFINITY : candidate.start)
                .thenComparing(Candidate::stableKey));
        return merged.stream().limit(MAX_MOMENTS).map(candidate -> candidate.toMoment(clipId, association)).toList();
    }

    private List<Candidate> transcriptCandidates(double duration, String text,
            List<ClipTranscriptionResponse.TimedTranscriptSegment> segments) {
        List<TranscriptCue> usable = deduplicateSegments(duration, segments);
        List<Candidate> result = new ArrayList<>();
        for (int index = 0; index < usable.size(); index++) {
            TranscriptCue cue = usable.get(index);
            String phrase = cue.segment().text().trim();
            String normalized = cue.normalized();
            boolean excited = containsCue(normalized, EXCITED);
            boolean notable = containsCue(normalized, NOTABLE);
            boolean repeatedPhrase = isIndependentRepetition(index, usable);
            if (!excited && !notable && !repeatedPhrase) continue;
            Double start = cue.start();
            Double end = cue.end();
            Candidate candidate = new Candidate(start, end);
            candidate.transcript.add(new TranscriptEvidence(limit(phrase, 240), start, end));
            candidate.contributors.add("transcript_cue");
            if (excited) candidate.labels.add("excited speech");
            if (notable) candidate.labels.add("notable phrase");
            if (repeatedPhrase) {
                candidate.contributors.add("repeated_phrase");
                candidate.labels.add("repeated phrase");
            }
            result.add(candidate);
        }
        if (result.isEmpty() && (usable.isEmpty()) && text != null && !text.isBlank()) {
            String phrase = text.trim();
            String normalized = normalize(phrase);
            if (containsCue(normalized, EXCITED) || containsCue(normalized, NOTABLE)) {
                Candidate candidate = new Candidate(null, null);
                candidate.transcript.add(new TranscriptEvidence(limit(phrase, 240), null, null));
                candidate.contributors.add("transcript_cue");
                candidate.labels.add(containsCue(normalized, EXCITED) ? "excited speech" : "notable phrase");
                result.add(candidate);
            }
        }
        return result;
    }

    private List<TranscriptCue> deduplicateSegments(double duration,
            List<ClipTranscriptionResponse.TimedTranscriptSegment> segments) {
        if (segments == null) return List.of();
        List<TranscriptCue> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var segment : segments) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) continue;
            boolean validRange = validTime(segment.startSeconds(), duration)
                    && validTime(segment.endSeconds(), duration) && segment.endSeconds() >= segment.startSeconds();
            Double start = validRange ? segment.startSeconds() : null;
            Double end = validRange ? segment.endSeconds() : null;
            String normalized = normalize(segment.text());
            String key = normalized + "|" + (start == null ? "untimed" : start + "|" + end);
            if (seen.add(key)) unique.add(new TranscriptCue(segment, normalized, start, end));
        }
        return unique;
    }

    private boolean isIndependentRepetition(int index, List<TranscriptCue> cues) {
        TranscriptCue cue = cues.get(index);
        if (!hasSubstantiveRepeatedPhrase(cue.normalized()) || cue.start() == null || cue.end() == null) return false;
        for (int otherIndex = 0; otherIndex < cues.size(); otherIndex++) {
            if (otherIndex == index) continue;
            TranscriptCue other = cues.get(otherIndex);
            if (!hasSubstantiveRepeatedPhrase(other.normalized()) || !cue.normalized().equals(other.normalized()) || other.start() == null || other.end() == null) continue;
            boolean disjoint = cue.end() <= other.start() || other.end() <= cue.start();
            if (disjoint) return true;
        }
        return false;
    }

    private boolean hasSubstantiveRepeatedPhrase(String normalized) {
        long substantiveWords = java.util.Arrays.stream(normalized.split(" "))
                .filter(word -> !word.isBlank() && !REPETITION_FUNCTION_WORDS.contains(word)).count();
        return substantiveWords >= 2 || STANDALONE_REACTION_CUES.contains(normalized);
    }

    private List<Candidate> chatCandidates(Instant streamStartedAt, int vodOffset, double duration,
            List<TwitchChatHighlightsResponse.Highlight> highlights) {
        Instant clipStart = streamStartedAt.plusSeconds(vodOffset);
        List<Candidate> result = new ArrayList<>();
        for (TwitchChatHighlightsResponse.Highlight highlight : highlights) {
            if (highlight == null || highlight.startTime() == null || highlight.endTime() == null) continue;
            double rawStart = Duration.between(clipStart, highlight.startTime()).toNanos() / 1_000_000_000.0;
            double rawEnd = Duration.between(clipStart, highlight.endTime()).toNanos() / 1_000_000_000.0;
            if (rawEnd <= 0 || rawStart >= duration) continue;
            double start = Math.max(0, rawStart);
            double end = Math.min(duration, rawEnd);
            if (end <= start) continue;
            Candidate candidate = new Candidate(start, end);
            Instant evidenceStart = highlight.startTime().isBefore(clipStart) ? clipStart : highlight.startTime();
            Instant clipEnd = clipStart.plusNanos((long) (duration * 1_000_000_000.0));
            Instant evidenceEnd = highlight.endTime().isAfter(clipEnd) ? clipEnd : highlight.endTime();
            List<String> samples = highlight.representativeMessages() == null ? List.of()
                    : highlight.representativeMessages().stream().limit(MAX_CHAT_MESSAGES).map(value -> limit(value, 180)).toList();
            candidate.chat.add(new ChatEvidence(evidenceStart, evidenceEnd, start, end,
                    highlight.score(), highlight.summary(), highlight.explanation(), highlight.messageCount(), samples));
            candidate.contributors.add("chat_activity");
            if (samples.stream().anyMatch(message -> containsCue(normalize(message), CHAT_REACTIONS))) {
                candidate.contributors.add("chat_reaction");
            }
            result.add(candidate);
        }
        return result;
    }

    private List<Candidate> mergeCandidates(List<Candidate> source) {
        List<Candidate> sorted = source.stream().sorted(Comparator
                .comparing((Candidate candidate) -> candidate.start == null ? Double.POSITIVE_INFINITY : candidate.start)
                .thenComparing(Candidate::stableKey)).toList();
        List<Candidate> merged = new ArrayList<>();
        for (Candidate candidate : sorted) {
            Candidate target = candidate.copy();
            boolean mergedExisting;
            do {
                mergedExisting = false;
                for (int i = 0; i < merged.size(); i++) {
                    Candidate existing = merged.get(i);
                    boolean sameRepeatedPhrase = existing.contributors.contains("repeated_phrase")
                            && target.contributors.contains("repeated_phrase")
                            && normalize(existing.transcriptText()).equals(normalize(target.transcriptText()));
                    boolean sameUntimedText = existing.start == null && target.start == null
                            && normalize(existing.transcriptText()).equals(normalize(target.transcriptText()));
                    if (existing.overlaps(target) || sameUntimedText) {
                        target.combine(existing);
                        merged.remove(i);
                        mergedExisting = true;
                        break;
                    }
                }
            } while (mergedExisting);
            merged.add(target);
        }
        return merged;
    }

    private boolean validTime(Double value, double duration) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= duration;
    }

    private boolean containsCue(String text, Set<String> cues) {
        return cues.stream().anyMatch(cue -> (" " + text + " ").contains(" " + normalize(cue) + " "));
    }

    private String normalize(String text) {
        return NORMALIZE.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private record TranscriptCue(ClipTranscriptionResponse.TimedTranscriptSegment segment, String normalized,
            Double start, Double end) { }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Candidate {
        private Double start;
        private Double end;
        private final List<TranscriptEvidence> transcript = new ArrayList<>();
        private final List<ChatEvidence> chat = new ArrayList<>();
        private final Set<String> contributors = new LinkedHashSet<>();
        private final Set<String> labels = new LinkedHashSet<>();
        private Candidate(Double start, Double end) { this.start = start; this.end = end; }
        private boolean overlaps(Candidate other) {
            return start != null && end != null && other.start != null && other.end != null
                    && other.start < end && start < other.end;
        }
        private void combine(Candidate other) {
            if (start == null || (other.start != null && other.start < start)) start = other.start;
            if (end == null || (other.end != null && other.end > end)) end = other.end;
            transcript.addAll(other.transcript); chat.addAll(other.chat);
            contributors.addAll(other.contributors); labels.addAll(other.labels);
        }
        private Candidate copy() {
            Candidate copy = new Candidate(start, end);
            copy.transcript.addAll(transcript); copy.chat.addAll(chat);
            copy.contributors.addAll(contributors); copy.labels.addAll(labels);
            return copy;
        }
        private String transcriptText() { return transcript.isEmpty() ? "" : transcript.getFirst().text(); }
        private String stableKey() {
            return (start == null ? "untimed" : String.format(Locale.ROOT, "%.3f", start)) + "|"
                    + normalizeStatic(transcriptText()) + "|" + chat.stream().map(e -> e.windowStartUtc().toString()).sorted().toList();
        }
        private int score() {
            int score = 0;
            if (contributors.contains("transcript_cue")) score += 40;
            if (contributors.contains("repeated_phrase")) score += 20;
            if (contributors.contains("chat_activity")) score += chatScoreContribution();
            if (contributors.contains("chat_reaction")) score += 5;
            if (contributors.contains("metadata_context")) score += 5;
            return Math.min(100, score);
        }
        private int chatScoreContribution() {
            return chat.stream().mapToInt(evidence -> (int) Math.round(evidence.score() * 0.25)).max().orElse(0);
        }
        private Moment toMoment(String clipId, String association) {
            String evidence = transcriptText();
            String description = !evidence.isBlank()
                    ? (labels.isEmpty() ? "Speech cue" : String.join(" and ", labels)) + ": “" + limitStatic(evidence, 120) + "”"
                    : chat.stream().map(ChatEvidence::summary).filter(summary -> summary != null && !summary.isBlank())
                            .findFirst().orElse("Chat activity: " + chat.stream().mapToInt(ChatEvidence::messageCount).sum()
                                    + " messages in this clip window.");
            List<TranscriptEvidence> transcriptEvidence = transcript.stream().distinct().limit(MAX_TRANSCRIPT_EVIDENCE).toList();
            List<ChatEvidence> chatEvidence = chat.stream().distinct().limit(2).toList();
            List<String> contributorList = List.copyOf(contributors);
            String selection = "Deterministic evidence ranking from " + String.join(", ", contributorList)
                    + chat.stream().map(e -> "; chat activity score " + String.format(Locale.ROOT, "%.1f", e.score())
                            + " from existing activity/reaction heuristic").findFirst().orElse("")
                    + "; score is a 0–100 ranking signal, not probability.";
            String id = "moment-" + hash(clipId + "|" + stableKey());
            return new Moment(id, description, start, end, score(), transcriptEvidence, chatEvidence, clipId,
                    association, selection, contributorList);
        }
        private static String normalizeStatic(String value) {
            return NORMALIZE.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        }
        private static String limitStatic(String value, int maximum) {
            return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
        }
    }
}
