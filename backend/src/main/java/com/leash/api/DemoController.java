package com.leash.api;

import com.leash.demo.DemoAgent;
import com.leash.demo.DemoScenario;
import com.leash.demo.LarderCatalogue;
import com.leash.policy.Mandate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoScenario scenario;
    private final DemoAgent agent;
    private final LarderCatalogue larder;

    public DemoController(DemoScenario scenario, DemoAgent agent, LarderCatalogue larder) {
        this.scenario = scenario;
        this.agent = agent;
        this.larder = larder;
    }

    /** The landing page's static example: the engine's decisions for the scenario, without touching the ledger. */
    @GetMapping("/ledger")
    public DemoLedger ledger() {
        return new DemoLedger(DemoScenario.groceryLeash(), scenario.run());
    }

    /**
     * Plays the video scenario for real: Larder bot shops, Leash decides, PayPal pays. {@code paceMs=0} runs it
     * synchronously and returns the results; the dashboard uses a pace so each step arrives live.
     */
    @PostMapping("/run")
    public ResponseEntity<DemoAgent.Run> run(@RequestParam(defaultValue = "1800") long paceMs,
                                             @RequestParam(defaultValue = "true") boolean reset) {
        if (paceMs < 0 || paceMs > 10_000) throw new IllegalArgumentException("paceMs must be between 0 and 10000");
        var run = agent.run(paceMs, reset);
        return ResponseEntity.status(run.async() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(run);
    }

    /** Clears the ledger and restores the demo leashes. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        agent.reset();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/larder")
    public List<LarderCatalogue.Product> products() {
        return larder.products();
    }

    public record DemoLedger(Mandate mandate, List<DemoScenario.Entry> entries) {}
}
