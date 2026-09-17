package com.ae2vm.vm.boundary;

import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import net.minecraft.init.Bootstrap;

/**
 * Port of the original FalsePositiveDiagnosticTest: runs every reference scenario
 * through the ported VM and prints a detailed plan (firings / used / missing) for
 * those that do NOT validate, so we can see exactly what the engine produces for
 * each capability family. Diagnostic-only (no assertions), like the original.
 */
class FalsePositiveDiagnosticTest {

    private final Ae2VmReferencePlanner planner = new Ae2VmReferencePlanner();

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void dumpAllFalsePositives() {
        for (ReferenceScenario s : ThunderboltReferenceScenarios.all()) {
            CraftPlan<String> plan;
            try {
                plan = planner.plan(s);
            } catch (Exception failure) {
                System.out.println("=== " + s.id() + " => ENGINE THROW: " + failure);
                failure.printStackTrace(System.out);
                continue;
            }
            if (plan == null) {
                System.out.println("=== " + s.id() + " => NULL PLAN");
                continue;
            }
            // Replicate ReferenceScenario.validate (package-private there):
            boolean valid = plan.supported() && plan.feasible() == s.expectedFeasible();
            if (s.expectedFeasible()) {
                valid = valid && plan.missing().isEmpty();
            } else {
                boolean any = false;
                for (Map<String, Long> baseline : s.minimalMissing()) {
                    boolean domainOk = plan.missing().entrySet().stream()
                            .filter(e -> e.getValue() != null && e.getValue() > 0)
                            .allMatch(e -> baseline.containsKey(e.getKey()));
                    if (!domainOk) {
                        continue;
                    }
                    boolean sufficient = baseline.entrySet().stream()
                            .allMatch(e -> plan.missing().getOrDefault(e.getKey(), 0L) >= e.getValue());
                    if (sufficient) {
                        any = true;
                        break;
                    }
                }
                valid = valid && any;
            }
            if (valid) {
                continue; // supported — skip
            }
            System.out.println("=== " + s.id()
                    + " target=" + s.target() + "x" + s.amount()
                    + " mode=" + s.materialMode() + " scale=" + s.scale()
                    + " expectedFeasible=" + s.expectedFeasible()
                    + " minimalMissing=" + s.minimalMissing());
            System.out.println("    feasible=" + plan.feasible()
                    + " missing=" + plan.missing()
                    + " used=" + plan.usedStock());
            System.out.println("    firings:");
            for (Map.Entry<CraftPattern<String>, Long> e : plan.firings().entrySet()) {
                var p = e.getKey();
                System.out.println("      " + p.output() + "x" + p.outputAmount()
                        + " <- " + p.inputs() + " * " + e.getValue());
            }
        }
    }
}
