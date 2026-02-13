package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.CaseState;

import java.util.List;

@Component(id = "audit-trail-view")
public class AuditTrailView extends View {

    public record AuditTrailEntry(
        String caseNumber,
        String status,
        boolean hasScreening,
        boolean hasSecretariat,
        boolean hasAudit,
        boolean hasDraft,
        int citationCount
    ) {}

    public record AuditTrailEntries(List<AuditTrailEntry> entries) {}

    @Consume.FromWorkflow(CaseProcessingWorkflow.class)
    public static class AuditTrailUpdater extends TableUpdater<AuditTrailEntry> {

        public Effect<AuditTrailEntry> onUpdate(CaseState state) {
            return effects().updateRow(new AuditTrailEntry(
                state.caseNumber(),
                state.status().name(),
                state.screening() != null,
                state.secretariat() != null,
                state.audit() != null,
                state.draft() != null,
                state.draft() != null ? state.draft().citations().size() : 0
            ));
        }
    }

    @Query("SELECT * AS entries FROM audit_trail")
    public QueryEffect<AuditTrailEntries> getAll() {
        return queryResult();
    }

    @Query("SELECT * FROM audit_trail WHERE caseNumber = :caseNumber")
    public QueryEffect<AuditTrailEntry> getByCaseNumber(String caseNumber) {
        return queryResult();
    }
}