package com.travelagent.integration;

import com.travelagent.mapper.UserMemoryMapper;
import com.travelagent.model.entity.UserMemoryFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("User memory mapper integration tests")
class UserMemoryMapperIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserMemoryMapper userMemoryMapper;

    @Test
    void upsertProfileAndFacts_persistsLongTermMemory() {
        userMemoryMapper.upsertProfile(7L, "likes family trips", "interests=family,culture");

        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(7L);
        fact.setMemoryType("preference");
        fact.setMemoryKey("attraction_interest");
        fact.setMemoryValue("family travel");
        fact.setConfidence(BigDecimal.valueOf(0.70));
        fact.setEvidenceCount(1);
        fact.setSource("test");
        fact.setSourceSummary("likes family trips");
        userMemoryMapper.upsertFact(fact);
        userMemoryMapper.upsertFact(fact);

        assertThat(userMemoryMapper.findProfileByUserId(7L).getProfileSummary())
                .contains("family");
        assertThat(userMemoryMapper.findActiveFactsByUserId(7L))
                .hasSize(1)
                .first()
                .satisfies(stored -> {
                    assertThat(stored.getEvidenceCount()).isEqualTo(2);
                    assertThat(stored.getMemoryValue()).isEqualTo("family travel");
                });
    }

    @Test
    void softDeleteFacts_hidesUserMemoryFacts() {
        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(8L);
        fact.setMemoryType("avoid");
        fact.setMemoryKey("avoid_constraint");
        fact.setMemoryValue("too much walking");
        fact.setConfidence(BigDecimal.valueOf(0.75));
        fact.setEvidenceCount(1);
        userMemoryMapper.upsertFact(fact);

        assertThat(userMemoryMapper.findActiveFactsByUserId(8L)).hasSize(1);

        userMemoryMapper.softDeleteFacts(8L);

        assertThat(userMemoryMapper.findActiveFactsByUserId(8L)).isEmpty();
    }
}
