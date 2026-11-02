package com.leash.mandate;

import com.leash.agent.Agent;
import com.leash.agent.AgentRepository;
import com.leash.api.NotFoundException;
import com.leash.policy.Mandate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Creates and changes leashes (agent + mandate), and snaps agents back. */
@Service
public class MandateService {

    /** What the API returns for one leash. */
    public record Leash(Agent agent, Mandate mandate, String sourceText) {}

    private final AgentRepository agents;
    private final MandateRepository mandates;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public MandateService(AgentRepository agents, MandateRepository mandates, Clock clock, ApplicationEventPublisher events) {
        this.agents = agents;
        this.mandates = mandates;
        this.clock = clock;
        this.events = events;
    }

    public List<Leash> list() {
        Map<String, MandateRepository.Stored> byAgent = mandates.findAll().stream()
                .collect(Collectors.toMap(s -> s.mandate().agentId(), Function.identity()));
        return agents.findAll().stream()
                .filter(a -> byAgent.containsKey(a.id()))
                .map(a -> new Leash(a, byAgent.get(a.id()).mandate(), byAgent.get(a.id()).sourceText()))
                .toList();
    }

    public Leash get(String agentId) {
        Agent a = agents.find(agentId).orElseThrow(() -> new NotFoundException("No agent called " + agentId));
        var m = mandates.findByAgent(agentId).orElseThrow(() -> new NotFoundException(agentId + " has no mandate"));
        return new Leash(a, m.mandate(), m.sourceText());
    }

    @Transactional
    public Leash create(String agentName, String kind, MandateInput input) {
        if (agentName == null || agentName.isBlank()) throw new IllegalArgumentException("agentName is required");
        String id = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        Agent a = new Agent(id, agentName.trim(), normaliseKind(kind), false, null, clock.instant());
        agents.insert(a);
        mandates.upsert(input.toMandate("m-" + id.substring(6), id), input.sourceText(), clock.instant());
        Leash leash = get(id);
        announce(leash);
        return leash;
    }

    /** Seeds a known leash (demo data). Idempotent. */
    @Transactional
    public void seed(DemoLeashes.Seed s) {
        if (agents.find(s.agentId()).isEmpty()) {
            agents.insert(new Agent(s.agentId(), s.agentName(), s.kind(), false, null, clock.instant()));
        } else {
            agents.update(agents.find(s.agentId()).orElseThrow().releashed());
        }
        mandates.upsert(s.mandate(), s.sourceText(), clock.instant());
    }

    @Transactional
    public Leash updateMandate(String agentId, MandateInput input) {
        Leash current = get(agentId);
        mandates.upsert(input.toMandate(current.mandate().id(), agentId),
                input.sourceText() != null ? input.sourceText() : current.sourceText(), clock.instant());
        Leash leash = get(agentId);
        announce(leash);
        return leash;
    }

    /** Snap back: the agent is revoked at once. The engine checks this before anything else. */
    @Transactional
    public Leash snap(String agentId) {
        Agent a = agents.find(agentId).orElseThrow(() -> new NotFoundException("No agent called " + agentId));
        if (!a.revoked()) agents.update(a.snapped(clock.instant()));
        Leash leash = get(agentId);
        announce(leash);
        return leash;
    }

    @Transactional
    public Leash releash(String agentId) {
        Agent a = agents.find(agentId).orElseThrow(() -> new NotFoundException("No agent called " + agentId));
        if (a.revoked()) agents.update(a.releashed());
        Leash leash = get(agentId);
        announce(leash);
        return leash;
    }

    private static String normaliseKind(String kind) {
        if (kind == null) return "other";
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "grocery", "office", "travel" -> k;
            default -> "other";
        };
    }

    /** Published when a leash is created, changed, snapped or re-leashed. The live stream forwards it. */
    public record LeashEvent(Leash leash) {}

    private void announce(Leash leash) {
        events.publishEvent(new LeashEvent(leash));
    }
}
