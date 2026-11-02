package com.leash.api;

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

    public LeashController(MandateService leashes) {
        this.leashes = leashes;
    }

    @GetMapping
    public List<Leash> list() {
        return leashes.list();
    }

    @GetMapping("/{agentId}")
    public Leash get(@PathVariable String agentId) {
        return leashes.get(agentId);
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
