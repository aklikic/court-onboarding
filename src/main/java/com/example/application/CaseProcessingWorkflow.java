package com.example.application;

import akka.Done;
import akka.javasdk.NotificationPublisher;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.example.domain.AuditResult;
import com.example.domain.CaseState;
import com.example.domain.CaseStatus;
import com.example.domain.DraftResult;
import com.example.domain.ScreeningResult;
import com.example.domain.SecretariatResult;

import static java.time.Duration.*;

@Component(id = "case-processing")
public class CaseProcessingWorkflow extends Workflow<CaseState> {

    private final ComponentClient componentClient;
    private final NotificationPublisher<String> notificationPublisher;

    public CaseProcessingWorkflow(ComponentClient componentClient,
                                   NotificationPublisher<String> notificationPublisher) {
        this.componentClient = componentClient;
        this.notificationPublisher = notificationPublisher;
    }

    public NotificationPublisher.NotificationStream<String> updates() {
        return notificationPublisher.stream();
    }

    // --- Command handlers ---

    public Effect<Done> start(String caseNumber) {
        if (currentState() != null) {
            return effects().error("Case already started");
        }
        return effects()
            .updateState(CaseState.create(caseNumber))
            .transitionTo(CaseProcessingWorkflow::screeningStep)
            .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<CaseState> getState() {
        if (currentState() == null) {
            return effects().error("Case not started");
        }
        return effects().reply(currentState());
    }

    public Effect<Done> approve() {
        if (currentState() == null) {
            return effects().error("Case not started");
        }
        if (currentState().status() != CaseStatus.AWAITING_HUMAN_APPROVAL) {
            return effects().error("Case is not awaiting approval, current status: " + currentState().status());
        }
        return effects()
            .updateState(currentState().withStatus(CaseStatus.APPROVED))
            .transitionTo(CaseProcessingWorkflow::publishStep)
            .thenReply(Done.getInstance());
    }

    public Effect<Done> reject(String reason) {
        if (currentState() == null) {
            return effects().error("Case not started");
        }
        if (currentState().status() != CaseStatus.AWAITING_HUMAN_APPROVAL) {
            return effects().error("Case is not awaiting approval, current status: " + currentState().status());
        }
        return effects()
            .updateState(currentState().withRejection(reason))
            .transitionTo(CaseProcessingWorkflow::reviseDraftStep)
            .thenReply(Done.getInstance());
    }

    public Effect<Done> resume() {
        if (currentState() == null) {
            return effects().error("Case not started");
        }
        if (currentState().status() != CaseStatus.FAILED) {
            return effects().error("Case is not in failed state, current status: " + currentState().status());
        }
        return effects()
            .updateState(currentState().withStatus(CaseStatus.RECEIVED))
            .transitionTo(CaseProcessingWorkflow::screeningStep)
            .thenReply(Done.getInstance());
    }

    public Effect<Done> continueFromAudit() {
        if (currentState() == null) {
            return effects().error("Case not started");
        }
        if (currentState().status() != CaseStatus.AUDIT_FAILED) {
            return effects().error("Case is not in audit failed state, current status: " + currentState().status());
        }
        return effects()
            .updateState(currentState().withStatus(CaseStatus.AUDIT_PASSED))
            .transitionTo(CaseProcessingWorkflow::draftingStep)
            .thenReply(Done.getInstance());
    }

    public Effect<Done> fail(String reason) {
        if (currentState() == null) {
            return effects().error("Case not started");
        }
        return effects()
            .updateState(currentState().withFailure(reason))
            .transitionTo(CaseProcessingWorkflow::failureStep)
            .thenReply(Done.getInstance());
    }

    // --- Settings ---

    @Override
    public WorkflowSettings settings() {
        return WorkflowSettings.builder()
            .defaultStepTimeout(ofMinutes(3))
            .defaultStepRecovery(maxRetries(2).failoverTo(CaseProcessingWorkflow::failureStep))
            .build();
    }

    // --- Steps ---

    @StepName("screening")
    private StepEffect screeningStep() {
        notificationPublisher.publish("Screening started for case " + currentState().caseNumber());

        ScreeningResult result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(ScreeningAgent::process)
            .invoke(currentState().caseNumber());

        notificationPublisher.publish("Screening completed: " + result.procedureType() + ", urgency " + result.urgency());

        return stepEffects()
            .updateState(currentState().withScreening(result))
            .thenTransitionTo(CaseProcessingWorkflow::secretariatStep);
    }

    @StepName("secretariat")
    private StepEffect secretariatStep() {
        notificationPublisher.publish("Secretariat processing started");

        SecretariatResult result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(SecretariatRoutineAgent::process)
            .invoke(currentState().caseNumber());

        notificationPublisher.publish("Secretariat completed: " + result.generatedActs().size() + " acts generated");

        return stepEffects()
            .updateState(currentState().withSecretariat(result))
            .thenTransitionTo(CaseProcessingWorkflow::auditStep);
    }

    @StepName("audit")
    private StepEffect auditStep() {
        notificationPublisher.publish("Consistency audit started");

        AuditResult result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(ConsistencyAuditAgent::process)
            .invoke(currentState().caseNumber());

        if (result.consistent()) {
            notificationPublisher.publish("Audit passed: no issues found");
            return stepEffects()
                .updateState(currentState().withAuditPassed(result))
                .thenTransitionTo(CaseProcessingWorkflow::draftingStep);
        } else {
            notificationPublisher.publish("Audit failed: " + result.issues().size() + " issues found - human intervention required");
            return stepEffects()
                .updateState(currentState().withAuditFailed(result))
                .thenPause();
        }
    }

    @StepName("drafting")
    private StepEffect draftingStep() {
        notificationPublisher.publish("Draft generation started");

        String auditSummary = currentState().audit() != null
            ? "Audit passed. No issues found."
            : "No audit available.";

        DraftResult result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(DraftingSupportAgent::process)
            .invoke(new DraftingSupportAgent.DraftRequest(currentState().caseNumber(), auditSummary));

        notificationPublisher.publish("Draft ready with " + result.citations().size() + " citations");

        return stepEffects()
            .updateState(currentState().withDraft(result))
            .thenTransitionTo(CaseProcessingWorkflow::awaitApprovalStep);
    }

    @StepName("await-approval")
    private StepEffect awaitApprovalStep() {
        notificationPublisher.publish("Awaiting human approval");

        return stepEffects()
            .updateState(currentState().withStatus(CaseStatus.AWAITING_HUMAN_APPROVAL))
            .thenPause();
    }

    @StepName("revise-draft")
    private StepEffect reviseDraftStep() {
        notificationPublisher.publish("Revising draft after rejection");

        String context = "Previous draft was rejected. Reason: " + currentState().rejectionReason()
            + ". Please revise the draft.";

        DraftResult result = componentClient
            .forAgent()
            .inSession(sessionId())
            .method(DraftingSupportAgent::process)
            .invoke(new DraftingSupportAgent.DraftRequest(currentState().caseNumber(), context));

        notificationPublisher.publish("Revised draft ready");

        return stepEffects()
            .updateState(currentState().withDraft(result))
            .thenTransitionTo(CaseProcessingWorkflow::awaitApprovalStep);
    }

    @StepName("publish")
    private StepEffect publishStep() {
        notificationPublisher.publish("Case approved and published");

        return stepEffects()
            .updateState(currentState().withStatus(CaseStatus.PUBLISHED))
            .thenEnd();
    }

    @StepName("failure")
    private StepEffect failureStep() {
        String failedDuring = switch (currentState().status()) {
            case RECEIVED, SCREENING -> "Screening";
            case SCREENING_COMPLETE, SECRETARIAT_PROCESSING -> "Secretariat processing";
            case SECRETARIAT_COMPLETE, AUDITING -> "Consistency audit";
            case AUDIT_PASSED, DRAFTING -> "Draft generation";
            case REJECTED -> "Draft revision";
            default -> "Unknown step";
        };

        String message = failedDuring + " failed after retries. Human intervention required.";
        notificationPublisher.publish("Workflow failed during: " + failedDuring);

        return stepEffects()
            .updateState(currentState().withFailure(message))
            .thenPause();
    }

    // --- Helpers ---

    private String sessionId() {
        return commandContext().workflowId();
    }
}