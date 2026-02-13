package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.CaseState;

import java.util.List;

@Component(id = "kpi-dashboard-view")
public class KPIDashboardView extends View {

    public record KPIEntry(
        String caseNumber,
        String status,
        boolean documentsComplete,
        boolean auditConsistent,
        int auditIssueCount
    ) {}

    public record KPIEntries(List<KPIEntry> entries) {}

    @Consume.FromWorkflow(CaseProcessingWorkflow.class)
    public static class KPIDashboardUpdater extends TableUpdater<KPIEntry> {

        public Effect<KPIEntry> onUpdate(CaseState state) {
            return effects().updateRow(new KPIEntry(
                state.caseNumber(),
                state.status().name(),
                state.screening() != null && state.screening().documentsComplete(),
                state.audit() != null && state.audit().consistent(),
                state.audit() != null ? state.audit().issues().size() : 0
            ));
        }
    }

    @Query("SELECT * AS entries FROM kpi_dashboard")
    public QueryEffect<KPIEntries> getAll() {
        return queryResult();
    }

    @Query("SELECT * AS entries FROM kpi_dashboard WHERE documentsComplete = false")
    public QueryEffect<KPIEntries> getIncompleteDocuments() {
        return queryResult();
    }

    @Query("SELECT * AS entries FROM kpi_dashboard WHERE auditConsistent = false")
    public QueryEffect<KPIEntries> getFailedAudits() {
        return queryResult();
    }
}