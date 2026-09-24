package com.travelagent.agent.memory;

import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.safety.SensitiveInfoGuard;
import com.travelagent.mapper.UserMemoryMapper;
import com.travelagent.model.entity.UserMemoryFact;
import com.travelagent.model.entity.UserMemoryProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserPreferenceMemoryService {

    private final SensitiveInfoGuard sensitiveInfoGuard;
    private final Map<Long, UserPreference> memory = new LinkedHashMap<>();

    @Autowired(required = false)
    private UserMemoryMapper userMemoryMapper;

    public UserPreferenceMemoryService(SensitiveInfoGuard sensitiveInfoGuard) {
        this.sensitiveInfoGuard = sensitiveInfoGuard;
    }

    public UserPreference upsertFromConstraints(Long userId, TravelConstraints constraints, String sourceText) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (userMemoryMapper != null) {
            return upsertPersistent(userId, constraints, sourceText, "constraints");
        }
        return upsertInMemory(userId, constraints, sourceText);
    }

    public UserPreference upsertFromConversation(Long userId, TravelConstraints currentConstraints,
                                                 String sourceText, String source) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (userMemoryMapper != null) {
            return upsertPersistent(userId, currentConstraints, sourceText, source);
        }
        return upsertInMemory(userId, currentConstraints, sourceText);
    }

    private UserPreference upsertInMemory(Long userId, TravelConstraints constraints, String sourceText) {
        UserPreference preference = memory.computeIfAbsent(userId, ignored -> {
            UserPreference created = new UserPreference();
            created.setUserId(userId);
            return created;
        });
        if (constraints != null) {
            copyConstraints(preference, constraints);
        }
        TravelConstraints extracted = extractPreferenceHints(sourceText);
        copyConstraints(preference, extracted);
        String sanitized = sensitiveInfoGuard.sanitize(sourceText);
        if (sanitized != null && !sanitized.isBlank()) {
            preference.setSourceSummary(summarize(sanitized));
        }
        preference.setUpdatedAt(Instant.now());
        return copy(preference);
    }

    public UserPreference loadRelevantPreference(Long userId, TravelConstraints currentConstraints) {
        if (userId == null) {
            return new UserPreference();
        }
        if (userMemoryMapper != null) {
            UserPreference preference = loadPersistent(userId);
            filterCurrentDestination(preference, currentConstraints);
            return preference;
        }
        UserPreference preference = memory.get(userId);
        if (preference == null) {
            return new UserPreference();
        }
        UserPreference copy = copy(preference);
        filterCurrentDestination(copy, currentConstraints);
        return copy;
    }

    public UserPreference applyExplicitPreferenceUpdate(Long userId, UserPreference update) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (userMemoryMapper != null) {
            return applyPersistentPreferenceUpdate(userId, update);
        }
        UserPreference target = memory.computeIfAbsent(userId, ignored -> {
            UserPreference created = new UserPreference();
            created.setUserId(userId);
            return created;
        });
        if (update == null) {
            return copy(target);
        }
        if (notBlank(update.getPacePreference())) target.setPacePreference(update.getPacePreference());
        if (notBlank(update.getHotelPreference())) target.setHotelPreference(sensitiveInfoGuard.sanitize(update.getHotelPreference()));
        if (notBlank(update.getFoodPreference())) target.setFoodPreference(sensitiveInfoGuard.sanitize(update.getFoodPreference()));
        if (update.getTypicalBudgetYuan() != null) target.setTypicalBudgetYuan(update.getTypicalBudgetYuan());
        merge(target.getAvoidConstraints(), update.getAvoidConstraints());
        merge(target.getHistoricalDestinations(), update.getHistoricalDestinations());
        merge(target.getCommonTransportModes(), update.getCommonTransportModes());
        merge(target.getAttractionInterests(), update.getAttractionInterests());
        target.setUpdatedAt(Instant.now());
        return copy(target);
    }

    public String buildPromptProfile(Long userId, TravelConstraints currentConstraints) {
        UserPreference preference = loadRelevantPreference(userId, currentConstraints);
        if (isEmpty(preference)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Long-term user profile (low priority soft preferences):");
        lines.add("Use these only as tie-breakers when the current task and short-term session memory are silent.");
        lines.add("If any current-session requirement conflicts with this profile, follow the current session.");
        addLine(lines, "Travel pace", preference.getPacePreference());
        addLine(lines, "Hotel preference", preference.getHotelPreference());
        addLine(lines, "Food preference", preference.getFoodPreference());
        addLine(lines, "Typical budget CNY", preference.getTypicalBudgetYuan() == null ? null : preference.getTypicalBudgetYuan().toPlainString());
        addListLine(lines, "Attraction interests", preference.getAttractionInterests());
        addListLine(lines, "Common transport modes", preference.getCommonTransportModes());
        addListLine(lines, "Avoid constraints", preference.getAvoidConstraints());
        addListLine(lines, "Historical destinations", preference.getHistoricalDestinations());
        if (notBlank(preference.getSourceSummary())) {
            lines.add("- Evidence summary: " + preference.getSourceSummary());
        }
        return String.join("\n", lines);
    }

    public void deleteUserMemory(Long userId) {
        if (userId == null) {
            return;
        }
        if (userMemoryMapper != null) {
            userMemoryMapper.softDeleteProfile(userId);
            userMemoryMapper.softDeleteFacts(userId);
        }
        memory.remove(userId);
    }

    private UserPreference upsertPersistent(Long userId, TravelConstraints constraints, String sourceText, String source) {
        String sanitized = sensitiveInfoGuard.sanitize(sourceText);
        String sourceSummary = notBlank(sanitized) ? summarize(sanitized) : null;
        UserPreference merged = loadPersistent(userId);
        merged.setUserId(userId);
        if (constraints != null) {
            copyConstraints(merged, constraints);
            persistFacts(userId, constraints, sourceSummary, source);
        }
        TravelConstraints extracted = extractPreferenceHints(sourceText);
        copyConstraints(merged, extracted);
        persistFacts(userId, extracted, sourceSummary, source);
        if (sourceSummary != null) {
            merged.setSourceSummary(sourceSummary);
        }
        merged.setUpdatedAt(Instant.now());
        userMemoryMapper.upsertProfile(userId, sourceSummary, buildCompactProfileSummary(merged));
        return loadPersistent(userId);
    }

    private UserPreference applyPersistentPreferenceUpdate(Long userId, UserPreference update) {
        if (update == null) {
            return loadPersistent(userId);
        }
        UserPreference merged = loadPersistent(userId);
        merged.setUserId(userId);
        if (notBlank(update.getPacePreference())) merged.setPacePreference(update.getPacePreference());
        if (notBlank(update.getHotelPreference())) merged.setHotelPreference(sensitiveInfoGuard.sanitize(update.getHotelPreference()));
        if (notBlank(update.getFoodPreference())) merged.setFoodPreference(sensitiveInfoGuard.sanitize(update.getFoodPreference()));
        if (update.getTypicalBudgetYuan() != null) merged.setTypicalBudgetYuan(update.getTypicalBudgetYuan());
        merge(merged.getAvoidConstraints(), update.getAvoidConstraints());
        merge(merged.getHistoricalDestinations(), update.getHistoricalDestinations());
        merge(merged.getCommonTransportModes(), update.getCommonTransportModes());
        merge(merged.getAttractionInterests(), update.getAttractionInterests());
        persistPreferenceFacts(userId, update, "explicit_update", summarize(buildCompactProfileSummary(update)));
        userMemoryMapper.upsertProfile(userId, merged.getSourceSummary(), buildCompactProfileSummary(merged));
        return loadPersistent(userId);
    }

    private UserPreference loadPersistent(Long userId) {
        UserPreference preference = new UserPreference();
        preference.setUserId(userId);
        UserMemoryProfile profile = userMemoryMapper.findProfileByUserId(userId);
        if (profile != null) {
            preference.setSourceSummary(profile.getSourceSummary());
        }
        List<UserMemoryFact> facts = userMemoryMapper.findActiveFactsByUserId(userId);
        for (UserMemoryFact fact : facts == null ? List.<UserMemoryFact>of() : facts) {
            applyFact(preference, fact);
        }
        return preference;
    }

    private void persistFacts(Long userId, TravelConstraints constraints, String sourceSummary, String source) {
        if (constraints == null) {
            return;
        }
        addFact(userId, "preference", "pace", constraints.getTravelPace(), confidence("pace"), source, sourceSummary);
        addFact(userId, "preference", "hotel", constraints.getHotelPreference(), confidence("hotel"), source, sourceSummary);
        addFact(userId, "preference", "food", constraints.getFoodPreference(), confidence("food"), source, sourceSummary);
        if (constraints.getBudgetYuan() != null && constraints.getBudgetYuan().compareTo(BigDecimal.ZERO) > 0) {
            addFact(userId, "budget", "typical_budget_yuan", constraints.getBudgetYuan().toPlainString(),
                    confidence("budget"), source, sourceSummary);
        }
        addFact(userId, "destination", "historical_destination", constraints.getDestination(),
                confidence("destination"), source, sourceSummary);
        addFacts(userId, "preference", "transport", constraints.getTransportPreference(), confidence("transport"), source, sourceSummary);
        addFacts(userId, "preference", "attraction_interest", constraints.getAttractionPreference(), confidence("attraction"), source, sourceSummary);
        addFacts(userId, "group", "special_group", constraints.getSpecialGroups(), confidence("group"), source, sourceSummary);
        addFacts(userId, "avoid", "avoid_constraint", constraints.getAvoid(), confidence("avoid"), source, sourceSummary);
    }

    private void persistPreferenceFacts(Long userId, UserPreference preference, String source, String sourceSummary) {
        addFact(userId, "preference", "pace", preference.getPacePreference(), confidence("pace"), source, sourceSummary);
        addFact(userId, "preference", "hotel", preference.getHotelPreference(), confidence("hotel"), source, sourceSummary);
        addFact(userId, "preference", "food", preference.getFoodPreference(), confidence("food"), source, sourceSummary);
        if (preference.getTypicalBudgetYuan() != null) {
            addFact(userId, "budget", "typical_budget_yuan", preference.getTypicalBudgetYuan().toPlainString(),
                    confidence("budget"), source, sourceSummary);
        }
        addFacts(userId, "destination", "historical_destination", preference.getHistoricalDestinations(), confidence("destination"), source, sourceSummary);
        addFacts(userId, "preference", "transport", preference.getCommonTransportModes(), confidence("transport"), source, sourceSummary);
        addFacts(userId, "preference", "attraction_interest", preference.getAttractionInterests(), confidence("attraction"), source, sourceSummary);
        addFacts(userId, "avoid", "avoid_constraint", preference.getAvoidConstraints(), confidence("avoid"), source, sourceSummary);
    }

    private void addFacts(Long userId, String type, String key, List<String> values,
                          BigDecimal confidence, String source, String sourceSummary) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addFact(userId, type, key, value, confidence, source, sourceSummary);
        }
    }

    private void addFact(Long userId, String type, String key, String value,
                         BigDecimal confidence, String source, String sourceSummary) {
        String sanitized = sensitiveInfoGuard.sanitize(value);
        if (!notBlank(sanitized)) {
            return;
        }
        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(userId);
        fact.setMemoryType(type);
        fact.setMemoryKey(key);
        fact.setMemoryValue(sanitized.trim());
        fact.setConfidence(confidence);
        fact.setEvidenceCount(1);
        fact.setSource(source);
        fact.setSourceSummary(sourceSummary);
        userMemoryMapper.upsertFact(fact);
    }

    private void applyFact(UserPreference preference, UserMemoryFact fact) {
        if (fact == null || !notBlank(fact.getMemoryValue())) {
            return;
        }
        String key = fact.getMemoryKey();
        String value = fact.getMemoryValue();
        switch (key) {
            case "pace" -> preference.setPacePreference(value);
            case "hotel" -> preference.setHotelPreference(value);
            case "food" -> preference.setFoodPreference(value);
            case "typical_budget_yuan" -> {
                try {
                    preference.setTypicalBudgetYuan(new BigDecimal(value));
                } catch (NumberFormatException ignored) {
                    // Ignore invalid legacy fact values.
                }
            }
            case "historical_destination" -> add(preference.getHistoricalDestinations(), value);
            case "transport" -> add(preference.getCommonTransportModes(), value);
            case "attraction_interest" -> add(preference.getAttractionInterests(), value);
            case "special_group" -> add(preference.getAttractionInterests(), value);
            case "avoid_constraint" -> add(preference.getAvoidConstraints(), value);
            default -> {
            }
        }
    }

    private void copyConstraints(UserPreference preference, TravelConstraints constraints) {
        if (notBlank(constraints.getTravelPace())) preference.setPacePreference(constraints.getTravelPace());
        if (notBlank(constraints.getHotelPreference())) preference.setHotelPreference(sensitiveInfoGuard.sanitize(constraints.getHotelPreference()));
        if (notBlank(constraints.getFoodPreference())) preference.setFoodPreference(sensitiveInfoGuard.sanitize(constraints.getFoodPreference()));
        if (constraints.getBudgetYuan() != null && constraints.getBudgetYuan().compareTo(BigDecimal.ZERO) > 0) {
            preference.setTypicalBudgetYuan(constraints.getBudgetYuan());
        }
        add(preference.getHistoricalDestinations(), constraints.getDestination());
        merge(preference.getAvoidConstraints(), constraints.getAvoid());
        merge(preference.getCommonTransportModes(), constraints.getTransportPreference());
        merge(preference.getAttractionInterests(), constraints.getAttractionPreference());
    }

    private TravelConstraints extractPreferenceHints(String sourceText) {
        TravelConstraints constraints = new TravelConstraints();
        if (!notBlank(sourceText)) {
            return constraints;
        }
        String text = sourceText.toLowerCase();
        List<String> interests = new ArrayList<>();
        List<String> avoid = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        if (containsAny(text, "family", "kids", "children", "\u4eb2\u5b50", "\u5b69\u5b50", "\u5c0f\u5b69")) {
            add(interests, "\u4eb2\u5b50");
            add(groups, "\u4eb2\u5b50");
        }
        if (containsAny(text, "culture", "cultural", "history", "historical", "museum",
                "\u4eba\u6587", "\u5386\u53f2", "\u535a\u7269\u9986")) {
            add(interests, "\u4eba\u6587");
        }
        if (containsAny(text, "nature", "natural", "\u81ea\u7136", "\u5c71\u6c34", "\u6237\u5916")) {
            add(interests, "\u81ea\u7136");
        }
        if (containsAny(text, "photo", "photography", "\u62cd\u7167", "\u6253\u5361")) {
            add(interests, "\u62cd\u7167");
        }
        if (containsAny(text, "relaxed", "slow", "easy", "\u6162\u8282\u594f", "\u8f7b\u677e", "\u4e0d\u8d76")) {
            constraints.setTravelPace("relaxed");
        }
        if (containsAny(text, "intensive", "packed", "\u591a\u770b", "\u7d27\u51d1")) {
            constraints.setTravelPace("intensive");
        }
        if (containsAny(text, "less walking", "short walk", "\u5c11\u8d70\u8def", "\u4e0d\u60f3\u8d70\u592a\u591a")) {
            add(avoid, "\u8d70\u8def\u592a\u591a");
        }
        if (containsAny(text, "commercial", "\u5546\u4e1a\u5316", "\u8d2d\u7269\u5e97")) {
            add(avoid, "\u5546\u4e1a\u5316");
        }
        constraints.setAttractionPreference(interests);
        constraints.setSpecialGroups(groups);
        constraints.setAvoid(avoid);
        return constraints;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void merge(List<String> target, List<String> source) {
        if (target == null || source == null) return;
        for (String item : source) {
            add(target, sensitiveInfoGuard.sanitize(item));
        }
    }

    private void add(List<String> target, String item) {
        if (target == null || !notBlank(item)) return;
        String value = item.trim();
        if (!target.contains(value)) {
            target.add(value);
        }
    }

    private UserPreference copy(UserPreference source) {
        UserPreference copy = new UserPreference();
        copy.setUserId(source.getUserId());
        copy.setPacePreference(source.getPacePreference());
        copy.setHotelPreference(source.getHotelPreference());
        copy.setFoodPreference(source.getFoodPreference());
        copy.setAvoidConstraints(new ArrayList<>(source.getAvoidConstraints()));
        copy.setHistoricalDestinations(new ArrayList<>(source.getHistoricalDestinations()));
        copy.setTypicalBudgetYuan(source.getTypicalBudgetYuan());
        copy.setCommonTransportModes(new ArrayList<>(source.getCommonTransportModes()));
        copy.setAttractionInterests(new ArrayList<>(source.getAttractionInterests()));
        copy.setSourceSummary(source.getSourceSummary());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String summarize(String value) {
        String compact = value.trim().replaceAll("\\s+", " ");
        return compact.length() <= 160 ? compact : compact.substring(0, 160);
    }

    private String buildCompactProfileSummary(UserPreference preference) {
        if (preference == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (notBlank(preference.getPacePreference())) parts.add("pace=" + preference.getPacePreference());
        if (!preference.getAttractionInterests().isEmpty()) parts.add("interests=" + String.join(",", preference.getAttractionInterests()));
        if (!preference.getCommonTransportModes().isEmpty()) parts.add("transport=" + String.join(",", preference.getCommonTransportModes()));
        if (notBlank(preference.getHotelPreference())) parts.add("hotel=" + preference.getHotelPreference());
        if (notBlank(preference.getFoodPreference())) parts.add("food=" + preference.getFoodPreference());
        if (!preference.getAvoidConstraints().isEmpty()) parts.add("avoid=" + String.join(",", preference.getAvoidConstraints()));
        if (preference.getTypicalBudgetYuan() != null) parts.add("budget=" + preference.getTypicalBudgetYuan().toPlainString());
        return parts.stream().filter(this::notBlank).collect(Collectors.joining("; "));
    }

    private void filterCurrentDestination(UserPreference preference, TravelConstraints currentConstraints) {
        if (preference != null && currentConstraints != null && currentConstraints.getDestination() != null) {
            preference.getHistoricalDestinations().removeIf(destination -> destination.equals(currentConstraints.getDestination()));
        }
    }

    private boolean isEmpty(UserPreference preference) {
        return preference == null
                || (!notBlank(preference.getPacePreference())
                && !notBlank(preference.getHotelPreference())
                && !notBlank(preference.getFoodPreference())
                && preference.getTypicalBudgetYuan() == null
                && preference.getAvoidConstraints().isEmpty()
                && preference.getHistoricalDestinations().isEmpty()
                && preference.getCommonTransportModes().isEmpty()
                && preference.getAttractionInterests().isEmpty()
                && !notBlank(preference.getSourceSummary()));
    }

    private void addLine(List<String> lines, String label, String value) {
        if (notBlank(value)) {
            lines.add("- " + label + ": " + value + " (confidence=medium)");
        }
    }

    private void addListLine(List<String> lines, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            lines.add("- " + label + ": " + String.join(", ", values) + " (confidence=medium)");
        }
    }

    private BigDecimal confidence(String category) {
        return switch (category) {
            case "budget", "destination" -> BigDecimal.valueOf(0.45);
            case "group", "avoid" -> BigDecimal.valueOf(0.75);
            case "pace", "hotel", "food", "transport", "attraction" -> BigDecimal.valueOf(0.70);
            default -> BigDecimal.valueOf(0.60);
        };
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
