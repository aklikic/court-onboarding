package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class AuditTrailViewIntegrationTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withWorkflowIncomingMessages(CaseProcessingWorkflow.class);
    }

    @Test
    public void shouldTrackCaseProgress() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var state = new CaseState(
            "CASE-2024-010",
            CaseStatus.AWAITING_HUMAN_APPROVAL,
            new ScreeningResult(ProcedureType.ORDINARY, Urgency.HIGH, true, List.of()),
            new SecretariatResult(List.of("Subpoena for response")),
            new AuditResult(true, List.of()),
            new DraftResult("Draft content", List.of("Civil Code Art. 927", "STJ-331/2024")),
            null,
            null
        );

        messages.publish(state, "workflow-1");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var entry = componentClient
                    .forView()
                    .method(AuditTrailView::getByCaseNumber)
                    .invoke("CASE-2024-010");

                assertThat(entry.caseNumber()).isEqualTo("CASE-2024-010");
                assertThat(entry.status()).isEqualTo("AWAITING_HUMAN_APPROVAL");
                assertThat(entry.hasScreening()).isTrue();
                assertThat(entry.hasSecretariat()).isTrue();
                assertThat(entry.hasAudit()).isTrue();
                assertThat(entry.hasDraft()).isTrue();
                assertThat(entry.citationCount()).isEqualTo(2);
            });
    }

    @Test
    public void shouldListAllCases() {
        var messages = testKit.getWorkflowIncomingMessages(CaseProcessingWorkflow.class);

        var state1 = new CaseState(
            "CASE-2024-011",
            CaseStatus.SCREENING_COMPLETE,
            new ScreeningResult(ProcedureType.SUMMARY, Urgency.LOW, true, List.of()),
            null, null, null, null, null
        );
        var state2 = new CaseState(
            "CASE-2024-012",
            CaseStatus.PUBLISHED,
            new ScreeningResult(ProcedureType.FAST_TRACK, Urgency.URGENT, true, List.of()),
            new SecretariatResult(List.of("Deadline notification")),
            new AuditResult(true, List.of()),
            new DraftResult("Final draft", List.of("Art. 477")),
            null, null
        );

        messages.publish(state1, "workflow-2");
        messages.publish(state2, "workflow-3");

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var result = componentClient
                    .forView()
                    .method(AuditTrailView::getAll)
                    .invoke();

                assertThat(result.entries()).hasSizeGreaterThanOrEqualTo(2);
                assertThat(result.entries())
                    .extracting(AuditTrailView.AuditTrailEntry::caseNumber)
                    .contains("CASE-2024-011", "CASE-2024-012");
            });
    }
}