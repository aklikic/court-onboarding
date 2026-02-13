package com.example.api;

import akka.Done;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import com.example.application.AuditTrailView;
import com.example.application.CasesByQueueView;
import com.example.application.CaseProcessingWorkflow;
import com.example.application.KPIDashboardView;
import com.example.domain.CaseState;

@HttpEndpoint("/cases")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class CaseEndpoint extends AbstractHttpEndpoint {

    private final ComponentClient componentClient;

    public CaseEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    public record StartCaseRequest(String caseNumber) {}
    public record RejectRequest(String reason) {}
    public record FailRequest(String reason) {}

    @Post("/{caseId}/start")
    public HttpResponse start(String caseId, StartCaseRequest request) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::start)
            .invoke(request.caseNumber());
        return HttpResponses.created();
    }

    @Get("/{caseId}")
    public CaseState get(String caseId) {
        return componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::getState)
            .invoke();
    }

    @Post("/{caseId}/approve")
    public HttpResponse approve(String caseId) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::approve)
            .invoke();
        return HttpResponses.ok();
    }

    @Post("/{caseId}/reject")
    public HttpResponse reject(String caseId, RejectRequest request) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::reject)
            .invoke(request.reason());
        return HttpResponses.ok();
    }

    @Post("/{caseId}/resume")
    public HttpResponse resume(String caseId) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::resume)
            .invoke();
        return HttpResponses.ok();
    }

    @Post("/{caseId}/continue")
    public HttpResponse continueFromAudit(String caseId) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::continueFromAudit)
            .invoke();
        return HttpResponses.ok();
    }

    @Post("/{caseId}/fail")
    public HttpResponse fail(String caseId, FailRequest request) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::fail)
            .invoke(request.reason());
        return HttpResponses.ok();
    }

    @Get("/{caseId}/updates")
    public HttpResponse updates(String caseId) {
        return HttpResponses.serverSentEvents(
            componentClient
                .forWorkflow(caseId)
                .notificationStream(CaseProcessingWorkflow::updates)
                .source()
        );
    }

    // --- Cases Queue View (Magistrate's inbox) ---

    @Get("/queue")
    public HttpResponse getQueue() {
        var source = componentClient
            .forView()
            .stream(CasesByQueueView::streamAll)
            .entriesSource(requestContext().lastSeenSseEventId().map(java.time.Instant::parse));
        return HttpResponses.serverSentEventsForView(source);
    }

    @Get("/queue/{status}")
    public CasesByQueueView.CaseQueueEntries getQueueByStatus(String status) {
        return componentClient
            .forView()
            .method(CasesByQueueView::getByStatus)
            .invoke(status);
    }

    // --- Audit Trail View ---

    @Get("/audit-trail")
    public AuditTrailView.AuditTrailEntries getAuditTrail() {
        return componentClient
            .forView()
            .method(AuditTrailView::getAll)
            .invoke();
    }

    @Get("/audit-trail/{caseNumber}")
    public AuditTrailView.AuditTrailEntry getAuditTrailEntry(String caseNumber) {
        return componentClient
            .forView()
            .method(AuditTrailView::getByCaseNumber)
            .invoke(caseNumber);
    }

    // --- KPI Dashboard View ---

    @Get("/kpi")
    public KPIDashboardView.KPIEntries getKPI() {
        return componentClient
            .forView()
            .method(KPIDashboardView::getAll)
            .invoke();
    }

    @Get("/kpi/incomplete-documents")
    public KPIDashboardView.KPIEntries getIncompleteDocuments() {
        return componentClient
            .forView()
            .method(KPIDashboardView::getIncompleteDocuments)
            .invoke();
    }

    @Get("/kpi/failed-audits")
    public KPIDashboardView.KPIEntries getFailedAudits() {
        return componentClient
            .forView()
            .method(KPIDashboardView::getFailedAudits)
            .invoke();
    }
}