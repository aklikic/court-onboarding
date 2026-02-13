package com.example.application;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class CaseProcessingWorkflowIntegrationTest extends TestKitSupport {

    private final TestModelProvider screeningModel = new TestModelProvider();
    private final TestModelProvider secretariatModel = new TestModelProvider();
    private final TestModelProvider auditModel = new TestModelProvider();
    private final TestModelProvider draftingModel = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
            .withDependencyProvider(new TestDependencyProvider())
            .withModelProvider(ScreeningAgent.class, screeningModel)
            .withModelProvider(SecretariatRoutineAgent.class, secretariatModel)
            .withModelProvider(ConsistencyAuditAgent.class, auditModel)
            .withModelProvider(DraftingSupportAgent.class, draftingModel);
    }

    @Test
    public void shouldProcessCaseToApproval() {
        var workflowId = UUID.randomUUID().toString();

        var screeningResult = new ScreeningResult(
            ProcedureType.ORDINARY, Urgency.MEDIUM, true, List.of());
        screeningModel.fixedResponse(JsonSupport.encodeToString(screeningResult));

        var secretariatResult = new SecretariatResult(
            List.of("Subpoena for response", "Deadline notification"));
        secretariatModel.fixedResponse(JsonSupport.encodeToString(secretariatResult));

        var auditResult = new AuditResult(true, List.of());
        auditModel.fixedResponse(JsonSupport.encodeToString(auditResult));

        var draftResult = new DraftResult(
            "Based on Civil Code Art. 927, the defendant is liable for damages.",
            List.of("Civil Code Art. 927", "Court Precedent STJ-331/2024"));
        draftingModel.fixedResponse(JsonSupport.encodeToString(draftResult));

        componentClient
            .forWorkflow(workflowId)
            .method(CaseProcessingWorkflow::start)
            .invoke("CASE-2024-001");

        // Wait for workflow to reach AWAITING_HUMAN_APPROVAL
        Awaitility.await()
            .ignoreExceptions()
            .atMost(30, SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(workflowId)
                    .method(CaseProcessingWorkflow::getState)
                    .invoke();
                assertThat(state.status()).isEqualTo(CaseStatus.AWAITING_HUMAN_APPROVAL);
                assertThat(state.screening()).isEqualTo(screeningResult);
                assertThat(state.secretariat()).isEqualTo(secretariatResult);
                assertThat(state.audit()).isEqualTo(auditResult);
                assertThat(state.draft()).isEqualTo(draftResult);
            });

        // Approve the case
        componentClient
            .forWorkflow(workflowId)
            .method(CaseProcessingWorkflow::approve)
            .invoke();

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(workflowId)
                    .method(CaseProcessingWorkflow::getState)
                    .invoke();
                assertThat(state.status()).isEqualTo(CaseStatus.PUBLISHED);
            });
    }

    @Test
    public void shouldHandleRejectionAndReviseDraft() {
        var workflowId = UUID.randomUUID().toString();

        var screeningResult = new ScreeningResult(
            ProcedureType.SUMMARY, Urgency.HIGH, true, List.of());
        screeningModel.fixedResponse(JsonSupport.encodeToString(screeningResult));

        var secretariatResult = new SecretariatResult(List.of("Subpoena for response"));
        secretariatModel.fixedResponse(JsonSupport.encodeToString(secretariatResult));

        var auditResult = new AuditResult(true, List.of());
        auditModel.fixedResponse(JsonSupport.encodeToString(auditResult));

        var draftResult = new DraftResult(
            "Initial draft content.",
            List.of("Civil Code Art. 927"));
        draftingModel.fixedResponse(JsonSupport.encodeToString(draftResult));

        componentClient
            .forWorkflow(workflowId)
            .method(CaseProcessingWorkflow::start)
            .invoke("CASE-2024-002");

        // Wait for AWAITING_HUMAN_APPROVAL
        Awaitility.await()
            .ignoreExceptions()
            .atMost(30, SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(workflowId)
                    .method(CaseProcessingWorkflow::getState)
                    .invoke();
                assertThat(state.status()).isEqualTo(CaseStatus.AWAITING_HUMAN_APPROVAL);
            });

        // Reject with reason - mock revised draft
        var revisedDraft = new DraftResult(
            "Revised draft with additional legal basis.",
            List.of("Civil Code Art. 927", "Insurance Regulatory Norm SUSEP-42"));
        draftingModel.fixedResponse(JsonSupport.encodeToString(revisedDraft));

        componentClient
            .forWorkflow(workflowId)
            .method(CaseProcessingWorkflow::reject)
            .invoke("Insufficient legal basis for moral damages claim");

        // Wait for workflow to pause again at AWAITING_HUMAN_APPROVAL with revised draft
        Awaitility.await()
            .ignoreExceptions()
            .atMost(30, SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(workflowId)
                    .method(CaseProcessingWorkflow::getState)
                    .invoke();
                assertThat(state.status()).isEqualTo(CaseStatus.AWAITING_HUMAN_APPROVAL);
                assertThat(state.draft()).isEqualTo(revisedDraft);
            });

        // Now approve
        componentClient
            .forWorkflow(workflowId)
            .method(CaseProcessingWorkflow::approve)
            .invoke();

        Awaitility.await()
            .ignoreExceptions()
            .atMost(10, SECONDS)
            .untilAsserted(() -> {
                var state = componentClient
                    .forWorkflow(workflowId)
                    .method(CaseProcessingWorkflow::getState)
                    .invoke();
                assertThat(state.status()).isEqualTo(CaseStatus.PUBLISHED);
            });
    }

    @Test
    public void shouldReceiveNotificationsDuringProcessing() throws Exception {
        var workflowId = UUID.randomUUID().toString();

        var screeningResult = new ScreeningResult(
            ProcedureType.ORDINARY, Urgency.MEDIUM, true, List.of());
        screeningModel.fixedResponse(JsonSupport.encodeToString(screeningResult));

        var secretariatResult = new SecretariatResult(
            List.of("Subpoena for response", "Deadline notification"));
        secretariatModel.fixedResponse(JsonSupport.encodeToString(secretariatResult));

        var auditResult = new AuditResult(true, List.of());
        auditModel.fixedResponse(JsonSupport.encodeToString(auditResult));

        var draftResult = new DraftResult(
            "Draft decision content.",
            List.of("Civil Code Art. 927"));
        draftingModel.fixedResponse(JsonSupport.encodeToString(draftResult));

        // Subscribe to SSE before triggering the workflow
        // Expect 9 notifications: screening start/complete, secretariat start/complete,
        // audit start/complete, drafting start/complete, awaiting approval
        var notifications = CompletableFuture.supplyAsync(() ->
            testKit.getSelfSseRouteTester().receiveFirstN(
                "/cases/" + workflowId + "/updates", 9, Duration.ofSeconds(30)));

        componentClient
            .forWorkflow(workflowId)
            .method(CaseProcessingWorkflow::start)
            .invoke("CASE-2024-003");

        var sse = notifications.get(30, TimeUnit.SECONDS);
        var events = sse.stream().map(evt -> evt.getData()).toList();

        assertThat(events).hasSize(9);
        assertThat(events.get(0)).contains("Screening started");
        assertThat(events.get(1)).contains("Screening completed");
        assertThat(events.get(2)).contains("Secretariat processing started");
        assertThat(events.get(3)).contains("Secretariat completed");
        assertThat(events.get(4)).contains("Consistency audit started");
        assertThat(events.get(5)).contains("Audit passed");
        assertThat(events.get(6)).contains("Draft generation started");
        assertThat(events.get(7)).contains("Draft ready");
        assertThat(events.get(8)).contains("Awaiting human approval");
    }

    private static class TestDependencyProvider implements akka.javasdk.DependencyProvider {
        private final CourtSystemServiceStub courtSystemService = new CourtSystemServiceStub();
        private final JurisprudenceServiceStub jurisprudenceService = new JurisprudenceServiceStub();

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getDependency(Class<T> clazz) {
            if (clazz == CourtSystemService.class) {
                return (T) courtSystemService;
            } else if (clazz == JurisprudenceService.class) {
                return (T) jurisprudenceService;
            } else {
                throw new IllegalArgumentException("Unknown dependency type: " + clazz);
            }
        }
    }
}