package com.leash.api;

import com.leash.demo.DemoScenario;
import com.leash.policy.Mandate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoScenario scenario;

    public DemoController(DemoScenario scenario) {
        this.scenario = scenario;
    }

    @GetMapping("/ledger")
    public DemoLedger ledger() {
        return new DemoLedger(DemoScenario.groceryLeash(), scenario.run());
    }

    public record DemoLedger(Mandate mandate, List<DemoScenario.Entry> entries) {}
}
