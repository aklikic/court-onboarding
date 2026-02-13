package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.CaseState;

import java.util.List;

@Component(id = "cases-by-queue-view")
public class CasesByQueueView extends View {

    public record CaseQueueEntry(
        String caseNumber,
        String status,
        String procedureType,
        String urgency,
        String failureMessage,
        String auditIssues
    ) {}

    public record CaseQueueEntries(List<CaseQueueEntry> entries) {}

    @Consume.FromWorkflow(CaseProcessingWorkflow.class)
    public static class CasesByQueueUpdater extends TableUpdater<CaseQueueEntry> {

        public Effect<CaseQueueEntry> onUpdate(CaseState state) {
            String auditIssues = "";
            if (state.audit() != null && state.audit().issues() != null && !state.audit().issues().isEmpty()) {
                auditIssues = String.join("; ", state.audit().issues());
            }
            return effects().updateRow(new CaseQueueEntry(
                state.caseNumber(),
                state.status().name(),
                state.screening() != null ? state.screening().procedureType().name() : "UNKNOWN",
                state.screening() != null ? state.screening().urgency().name() : "UNKNOWN",
                state.failureMessage() != null ? state.failureMessage() : "",
                auditIssues
            ));
        }
    }

    @Query("SELECT * AS entries FROM cases_by_queue WHERE status = :status")
    public QueryEffect<CaseQueueEntries> getByStatus(String status) {
        return queryResult();
    }

    @Query("SELECT * AS entries FROM cases_by_queue")
    public QueryEffect<CaseQueueEntries> getAll() {
        return queryResult();
    }

    @Query(value = "SELECT * FROM cases_by_queue", streamUpdates = true)
    public QueryStreamEffect<CaseQueueEntry> streamAll() {
        return queryStreamResult();
    }
}