package com.leash.api;

import com.leash.learning.LearningService;
import com.leash.learning.WalkReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Mandate learning (slack suggestions, accept / not now, the change log) and weekly walk reports. Human-facing only. */
@RestController
public class LearningController {

    public record SlackChoice(@Positive long toMinor) {}

    private final LearningService learning;
    private final WalkReportService reports;

    public LearningController(LearningService learning, WalkReportService reports) {
        this.learning = learning;
        this.reports = reports;
    }

    /** 200 with the suggestion, 204 when there's nothing to suggest. */
    @GetMapping("/api/leashes/{agentId}/suggestion")
    public ResponseEntity<LearningService.SuggestionView> suggestion(@PathVariable String agentId) {
        return learning.suggestion(agentId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** One click: raises the slack to the suggested amount. 409 if the suggestion changed since it was shown. */
    @com.leash.auth.DemoScoped(com.leash.auth.DemoScoped.Target.AGENT)
    @PostMapping("/api/leashes/{agentId}/suggestion/accept")
    public LearningService.LeashChange accept(@PathVariable String agentId, @Valid @RequestBody SlackChoice choice) {
        return learning.accept(agentId, choice.toMinor());
    }

    @com.leash.auth.DemoScoped(com.leash.auth.DemoScoped.Target.AGENT)
    @PostMapping("/api/leashes/{agentId}/suggestion/dismiss")
    public ResponseEntity<Void> dismiss(@PathVariable String agentId, @Valid @RequestBody SlackChoice choice) {
        learning.dismiss(agentId, choice.toMinor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/leash-changes")
    public List<LearningService.LeashChange> changes(@RequestParam(required = false) String agentId) {
        return learning.changes(agentId);
    }

    @GetMapping("/api/walk-reports")
    public List<WalkReportService.WalkReport> walkReports() {
        return reports.all();
    }

    @GetMapping("/api/walk-reports/{agentId}")
    public WalkReportService.WalkReport walkReport(@PathVariable String agentId) {
        return reports.report(agentId);
    }
}
