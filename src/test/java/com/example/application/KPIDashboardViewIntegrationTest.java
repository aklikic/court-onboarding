package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class KPIDashboardViewIntegrationTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withWorkflowIncomingMessages(CaseProcessingWorkflow.class);
    }

    @Test
    public void shouldTrackKPIMetrics() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var state = new CaseState(
            "CASE-2024-030",
            CaseStatus.AWAITING_HUMAN_APPROVAL,
            new ScreeningResult(ProcedureType.ORDINARY, Urgency.HIGH, true, List.of()),
            new SecretariatResult(List.of("Subpoena")),
            new AuditResult(true, List.of()),
            new DraftResult("Draft", List.of("Art. 927")),
            null, null
        );

        messages.publish(state, "wf-kpi-1");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var result = componentClient
                    .forView()
                    .method(KPIDashboardView::getAll)
                    .invoke();

                assertThat(result.entries())
                    .extracting(KPIDashboardView.KPIEntry::caseNumber)
                    .contains("CASE-2024-030");

                var entry = result.entries().stream()
                    .filter(e -> e.caseNumber().equals("CASE-2024-030"))
                    .findFirst().orElseThrow();
                assertThat(entry.documentsComplete()).isTrue();
                assertThat(entry.auditConsistent()).isTrue();
                assertThat(entry.auditIssueCount()).isEqualTo(0);
            });
    }

    @Test
    public void shouldFilterIncompleteDocuments() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var completeCase = new CaseState(
            "CASE-2024-031",
            CaseStatus.SCREENING_COMPLETE,
            new ScreeningResult(ProcedureType.ORDINARY, Urgency.LOW, true, List.of()),
            null, null, null, null, null
        );
        var incompleteCase = new CaseState(
            "CASE-2024-032",
            CaseStatus.SCREENING_COMPLETE,
            new ScreeningResult(ProcedureType.SUMMARY, Urgency.HIGH, false, List.of("Power of attorney", "ID copy")),
            null, null, null, null, null
        );

        messages.publish(completeCase, "wf-kpi-2");
        messages.publish(incompleteCase, "wf-kpi-3");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var result = componentClient
                    .forView()
                    .method(KPIDashboardView::getIncompleteDocuments)
                    .invoke();

                assertThat(result.entries()).hasSize(1);
                assertThat(result.entries().getFirst().caseNumber()).isEqualTo("CASE-2024-032");
                assertThat(result.entries().getFirst().documentsComplete()).isFalse();
            });
    }

    @Test
    public void shouldFilterFailedAudits() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var passedCase = new CaseState(
            "CASE-2024-033",
            CaseStatus.AUDIT_PASSED,
            new ScreeningResult(ProcedureType.ORDINARY, Urgency.MEDIUM, true, List.of()),
            new SecretariatResult(List.of("Subpoena")),
            new AuditResult(true, List.of()),
            null, null, null
        );
        var failedCase = new CaseState(
            "CASE-2024-034",
            CaseStatus.AUDIT_FAILED,
            new ScreeningResult(ProcedureType.FAST_TRACK, Urgency.URGENT, true, List.of()),
            new SecretariatResult(List.of("Deadline")),
            new AuditResult(false, List.of("Contradictory dates", "Missing signature")),
            null, null, null
        );

        messages.publish(passedCase, "wf-kpi-4");
        messages.publish(failedCase, "wf-kpi-5");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var result = componentClient
                    .forView()
                    .method(KPIDashboardView::getFailedAudits)
                    .invoke();

                assertThat(result.entries()).hasSize(1);
                var entry = result.entries().getFirst();
                assertThat(entry.caseNumber()).isEqualTo("CASE-2024-034");
                assertThat(entry.auditConsistent()).isFalse();
                assertThat(entry.auditIssueCount()).isEqualTo(2);
            });
    }
}