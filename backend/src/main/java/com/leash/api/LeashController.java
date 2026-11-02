package com.leash.api;

import com.leash.ledger.LedgerService;
import com.leash.mandate.MandateInput;
import com.leash.mandate.MandateService;
import com.leash.mandate.MandateService.Leash;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/leashes")
public class LeashController {

    private final MandateService leashes;
    private final LedgerService ledger;

    public LeashController(MandateService leashes, LedgerService ledger) {
        this.leashes = leashes;
        this.ledger = ledger;
    }

    /** A leash plus how much of its length is used this period: what a leash card shows. */
    public record LeashCard(com.leash.agent.Agent agent, com.leash.policy.Mandate mandate, String sourceText,
                            LedgerService.Usage usage) {
        static LeashCard of(Leash l, LedgerService ledger) {
            return new LeashCard(l.agent(), l.mandate(), l.sourceText(), ledger.usage(l.mandate()));
        }
    }

    @GetMapping
    public List<LeashCard> list() {
        return leashes.list().stream().map(l -> LeashCard.of(l, ledger)).toList();
    }

    @GetMapping("/{agentId}")
    public LeashCard get(@PathVariable String agentId) {
        return LeashCard.of(leashes.get(agentId), ledger);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Leash create(@Valid @RequestBody NewLeash body) {
        return leashes.create(body.agentName(), body.kind(), body.mandate());
    }

    @PutMapping("/{agentId}/mandate")
    public Leash updateMandate(@PathVariable String agentId, @Valid @RequestBody MandateInput body) {
        return leashes.updateMandate(agentId, body);
    }

    /** Snap back: revoke the agent instantly. */
    @PostMapping("/{agentId}/snap")
    public Leash snap(@PathVariable String agentId) {
        return leashes.snap(agentId);
    }

    /** Undo a snap back. */
    @PostMapping("/{agentId}/releash")
    public Leash releash(@PathVariable String agentId) {
        return leashes.releash(agentId);
    }

    public record NewLeash(@NotBlank String agentName, String kind, @NotNull @Valid MandateInput mandate) {}
}
