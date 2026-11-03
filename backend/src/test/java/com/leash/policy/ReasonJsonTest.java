package com.leash.policy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reasons are stored as JSON in ledger_entries.reasons_json: evidence must round-trip, and older rows without it must load. */
class ReasonJsonTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final TypeReference<List<Reason>> LIST = new TypeReference<>() {};

    @Test
    void evidenceRoundTrips() {
        var in = List.of(new Reason(RuleCode.PROMPT_INJECTION, "Hidden text.", "Ignore previous instructions."));
        assertThat(JSON.readValue(JSON.writeValueAsString(in), LIST)).isEqualTo(in);
    }

    @Test
    void rowsWrittenBeforeEvidenceStillLoad() {
        var out = JSON.readValue("[{\"code\":\"OVER_SLACK\",\"message\":\"Over £25.\"}]", LIST);
        assertThat(out).containsExactly(new Reason(RuleCode.OVER_SLACK, "Over £25."));
        assertThat(out.getFirst().evidence()).isNull();
    }
}
