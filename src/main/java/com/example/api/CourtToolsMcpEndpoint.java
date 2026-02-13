package com.example.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.mcp.McpEndpoint;
import akka.javasdk.annotations.mcp.McpTool;
import akka.javasdk.client.ComponentClient;
import com.example.application.CaseProcessingWorkflow;
import com.example.application.CasesByQueueView;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@McpEndpoint(serverName = "court-tools", serverVersion = "1.0.0")
public class CourtToolsMcpEndpoint {

    private final ComponentClient componentClient;

    public CourtToolsMcpEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    @McpTool(
        name = "get_case",
        description = "Retrieves the full state of a court case including screening, secretariat, audit, and draft results."
    )
    public String getCase(
        @Description("The workflow ID of the case to retrieve") String caseId
    ) {
        var state = componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::getState)
            .invoke();
        return JsonSupport.encodeToString(state);
    }

    @McpTool(
        name = "list_cases_by_status",
        description = "Lists all court cases filtered by their processing status. Common statuses: AWAITING_HUMAN_APPROVAL, PUBLISHED, FAILED, SCREENING, DRAFTING."
    )
    public String listCasesByStatus(
        @Description("The case status to filter by, e.g. AWAITING_HUMAN_APPROVAL") String status
    ) {
        var entries = componentClient
            .forView()
            .method(CasesByQueueView::getByStatus)
            .invoke(status);
        return JsonSupport.encodeToString(entries);
    }

    @McpTool(
        name = "list_all_cases",
        description = "Lists all court cases in the system with their current status, procedure type, and urgency."
    )
    public String listAllCases() {
        var entries = componentClient
            .forView()
            .method(CasesByQueueView::getAll)
            .invoke();
        return JsonSupport.encodeToString(entries);
    }

    @McpTool(
        name = "approve_case",
        description = "Approves a court case that is awaiting human approval. Only works when the case status is AWAITING_HUMAN_APPROVAL."
    )
    public String approveCase(
        @Description("The workflow ID of the case to approve") String caseId
    ) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::approve)
            .invoke();
        return "Case " + caseId + " approved successfully.";
    }

    @McpTool(
        name = "reject_case",
        description = "Rejects a court case that is awaiting human approval with a reason. The case will be sent back for draft revision."
    )
    public String rejectCase(
        @Description("The workflow ID of the case to reject") String caseId,
        @Description("The reason for rejecting the case") String reason
    ) {
        componentClient
            .forWorkflow(caseId)
            .method(CaseProcessingWorkflow::reject)
            .invoke(reason);
        return "Case " + caseId + " rejected. Reason: " + reason;
    }
}