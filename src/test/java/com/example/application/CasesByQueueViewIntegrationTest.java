package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class CasesByQueueViewIntegrationTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withWorkflowIncomingMessages(CaseProcessingWorkflow.class);
    }

    @Test
    public void shouldFilterByStatus() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var awaitingCase = new CaseState(
            "CASE-2024-020",
            CaseStatus.AWAITING_HUMAN_APPROVAL,
            new ScreeningResult(ProcedureType.ORDINARY, Urgency.HIGH, true, List.of()),
            new SecretariatResult(List.of("Subpoena")),
            new AuditResult(true, List.of()),
            new DraftResult("Draft", List.of("Art. 927")),
            null, null
        );
        var publishedCase = new CaseState(
            "CASE-2024-021",
            CaseStatus.PUBLISHED,
            new ScreeningResult(ProcedureType.SUMMARY, Urgency.LOW, true, List.of()),
            new SecretariatResult(List.of("Deadline")),
            new AuditResult(true, List.of()),
            new DraftResult("Final", List.of("Art. 477")),
            null, null
        );

        messages.publish(awaitingCase, "wf-queue-1");
        messages.publish(publishedCase, "wf-queue-2");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var result = componentClient
                    .forView()
                    .method(CasesByQueueView::getByStatus)
                    .invoke("AWAITING_HUMAN_APPROVAL");

                assertThat(result.entries()).hasSize(1);
                var entry = result.entries().getFirst();
                assertThat(entry.caseNumber()).isEqualTo("CASE-2024-020");
                assertThat(entry.procedureType()).isEqualTo("ORDINARY");
                assertThat(entry.urgency()).isEqualTo("HIGH");
            });
    }

    @Test
    public void shouldListAllCases() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var case1 = new CaseState(
            "CASE-2024-022",
            CaseStatus.SCREENING_COMPLETE,
            new ScreeningResult(ProcedureType.FAST_TRACK, Urgency.URGENT, true, List.of()),
            null, null, null, null, null
        );
        var case2 = new CaseState(
            "CASE-2024-023",
            CaseStatus.DRAFTING,
            new ScreeningResult(ProcedureType.ORDINARY, Urgency.MEDIUM, true, List.of()),
            new SecretariatResult(List.of("Subpoena")),
            new AuditResult(true, List.of()),
            null, null, null
        );

        messages.publish(case1, "wf-queue-3");
        messages.publish(case2, "wf-queue-4");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var result = componentClient
                    .forView()
                    .method(CasesByQueueView::getAll)
                    .invoke();

                assertThat(result.entries()).hasSizeGreaterThanOrEqualTo(2);
                assertThat(result.entries())
                    .extracting(CasesByQueueView.CaseQueueEntry::caseNumber)
                    .contains("CASE-2024-022", "CASE-2024-023");
            });
    }
}