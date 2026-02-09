# Court Onboarding - AI Agents for Courts

AI Agents to support court activities -- enhancing analysis, organization, and service capacity without replacing human decision-making.

## Architecture Overview

### High-Level System Architecture

```mermaid
graph TB
    subgraph "External Systems"
        CourtSys["Court System<br/>(Case Database)"]
        Juris["Jurisprudence<br/>Repository"]
    end

    subgraph "Akka Service - Court Onboarding"
        subgraph "API Layer"
            HTTP["CaseEndpoint<br/>@HttpEndpoint"]
            MCP["CourtToolsMcpEndpoint<br/>@McpEndpoint"]
        end

        subgraph "Ingress (Consumers)"
            CourtEventConsumer["CourtEventConsumer<br/>@Consume.FromTopic"]
        end

        subgraph "Orchestration (Single Source of Truth)"
            CaseWorkflow["CaseProcessingWorkflow<br/>Workflow&lt;CaseState&gt;"]
        end

        subgraph "AI Agents"
            ScreeningAgent["ScreeningAgent<br/>(Initial Screening)"]
            AuditAgent["ConsistencyAuditAgent<br/>(Consistency Audit)"]
            SecretariatAgent["SecretariatRoutineAgent<br/>(Secretariat Automation)"]
            DraftingAgent["DraftingSupportAgent<br/>(Drafting Support)"]
        end

        subgraph "Query (Views)"
            QueueView["CasesByQueueView<br/>@Consume.FromWorkflow"]
            KPIView["KPIDashboardView<br/>@Consume.FromWorkflow"]
            AuditView["AuditTrailView<br/>@Consume.FromWorkflow"]
        end

        subgraph "Notifications"
            ApprovalConsumer["ApprovalNotificationConsumer<br/>@Consume.FromWorkflow"]
        end
    end

    subgraph "External"
        Topic["Message Broker<br/>(approval-requests topic)"]
    end

    subgraph "Human Gate"
        Magistrate["Magistrate / Server"]
    end

    CourtSys -->|events| CourtEventConsumer
    CourtEventConsumer -->|start| CaseWorkflow
    CaseWorkflow -->|step 1| ScreeningAgent
    CaseWorkflow -->|step 2| SecretariatAgent
    CaseWorkflow -->|step 3| AuditAgent
    CaseWorkflow -->|step 4| DraftingAgent

    ScreeningAgent -.->|"MCP: search_case"| CourtSys
    AuditAgent -.->|"MCP: search_case"| CourtSys
    AuditAgent -.->|"MCP: search_jurisprudence"| Juris
    DraftingAgent -.->|"MCP: search_jurisprudence"| Juris
    SecretariatAgent -.->|"MCP: search_case, update_case"| CourtSys

    CaseWorkflow -->|state changes| QueueView
    CaseWorkflow -->|state changes| KPIView
    CaseWorkflow -->|state changes| AuditView
    CaseWorkflow -->|state changes| ApprovalConsumer
    ApprovalConsumer -->|"push when AWAITING_APPROVAL"| Topic
    Topic -->|notification| Magistrate

    Magistrate -->|"1. GET /cases?status=AWAITING_APPROVAL<br/>(inbox)"| HTTP
    HTTP -->|query| QueueView
    HTTP -->|query| KPIView
    HTTP -->|query| AuditView
    Magistrate -->|"2. GET /cases/{id}<br/>(review draft + evidence)"| HTTP
    Magistrate -->|"3. POST /cases/{id}/approve<br/>or /reject"| HTTP
    HTTP -->|commands| CaseWorkflow
    MCP -->|query| QueueView

    style CourtSys fill:#f9d4a0,stroke:#e6a050
    style Juris fill:#f9d4a0,stroke:#e6a050
    style Magistrate fill:#f9d4a0,stroke:#e6a050
    style Topic fill:#f9d4a0,stroke:#e6a050

    linkStyle 7,8,9,10,11 stroke:#1a73e8,stroke-width:2px
```

### Case Processing Workflow (Lifecycle)

The `CaseProcessingWorkflow` is the single source of truth. Its `CaseState` holds all verifications, RAG evidence, agent results, and human approvals. Views subscribe directly to Workflow state changes.

When the Workflow reaches `AWAITING_HUMAN_APPROVAL`, it pauses and waits for an external command. The `CasesByQueueView` acts as the Magistrate's inbox (queryable by status). The Magistrate reviews case details and approves/rejects via `CaseEndpoint`, which sends a command back to the Workflow.

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: Court System Event Ingested

    RECEIVED --> SCREENING: startScreeningStep
    SCREENING --> SCREENING_COMPLETE: ScreeningAgent classifies<br/>rite + urgency + completeness

    SCREENING_COMPLETE --> SECRETARIAT_PROCESSING: startSecretariatStep
    SECRETARIAT_PROCESSING --> SECRETARIAT_COMPLETE: SecretariatAgent generates<br/>subpoenas, deadline checks

    SECRETARIAT_COMPLETE --> AUDITING: startAuditStep
    AUDITING --> AUDIT_PASSED: ConsistencyAuditAgent<br/>no issues found
    AUDITING --> AUDIT_FAILED: ConsistencyAuditAgent<br/>inconsistencies detected

    AUDIT_FAILED --> SECRETARIAT_PROCESSING: compensate & fix

    AUDIT_PASSED --> DRAFTING: startDraftingStep
    DRAFTING --> DRAFT_READY: DraftingSupportAgent<br/>creates draft via RAG

    DRAFT_READY --> AWAITING_HUMAN_APPROVAL: step ends,<br/>workflow pauses

    state "AWAITING_HUMAN_APPROVAL" as AWAITING_HUMAN_APPROVAL
    note right of AWAITING_HUMAN_APPROVAL
        Workflow waits for external command.
        Magistrate uses CaseEndpoint:
        1. GET /cases?status=AWAITING_APPROVAL (inbox via View)
        2. GET /cases/{id} (review draft + RAG citations)
        3. POST /cases/{id}/approve or /reject
    end note

    AWAITING_HUMAN_APPROVAL --> APPROVED: approve command<br/>via CaseEndpoint
    AWAITING_HUMAN_APPROVAL --> REJECTED: reject command<br/>via CaseEndpoint

    REJECTED --> DRAFTING: revise draft

    APPROVED --> PUBLISHED: publishStep<br/>push to Court System
    PUBLISHED --> [*]
```

### Akka Component Detail Map

```mermaid
graph LR
    subgraph "domain package"
        CaseState["CaseState<br/>(workflow state record)"]
        ScreeningResult["ScreeningResult"]
        AuditResult["AuditResult"]
        DraftDocument["DraftDocument"]
        SecretariatAct["SecretariatAct"]
    end

    subgraph "application package"
        Workflow["CaseProcessingWorkflow<br/>Workflow&lt;CaseState&gt;"]
        SA["ScreeningAgent<br/>extends Agent"]
        CA["ConsistencyAuditAgent<br/>extends Agent"]
        SRA["SecretariatRoutineAgent<br/>extends Agent"]
        DA["DraftingSupportAgent<br/>extends Agent"]
        QV["CasesByQueueView<br/>extends View"]
        KV["KPIDashboardView<br/>extends View"]
        AV["AuditTrailView<br/>extends View"]
    end

    subgraph "api package"
        EP["CaseEndpoint<br/>@HttpEndpoint /cases"]
        MP["CourtToolsMcpEndpoint<br/>@McpEndpoint"]
    end

    Workflow -->|manages| CaseState
    Workflow -->|calls| SA
    Workflow -->|calls| CA
    Workflow -->|calls| SRA
    Workflow -->|calls| DA
    SA -->|returns| ScreeningResult
    CA -->|returns| AuditResult
    DA -->|returns| DraftDocument
    SRA -->|returns| SecretariatAct
    QV -->|@Consume.FromWorkflow| Workflow
    KV -->|@Consume.FromWorkflow| Workflow
    AV -->|@Consume.FromWorkflow| Workflow
    EP -->|queries| QV
    EP -->|queries| KV
    EP -->|queries| AV
    EP -->|commands| Workflow
```

### Agent Tool Integration (MCP)

Each agent connects to external systems exclusively via MCP tools. For the POC, MCP tools are implemented as **stubs** returning hardcoded data. The RAG pipeline behind the Jurisprudence Repository is out of scope.

```mermaid
graph TB
    subgraph "AI Agents"
        SA["ScreeningAgent"]
        CA["ConsistencyAuditAgent"]
        SRA["SecretariatRoutineAgent"]
        DA["DraftingSupportAgent"]
    end

    subgraph "MCP Tools (Stubs for POC)"
        CourtSys["Court System<br/>(Case Database)"]
        Juris["Jurisprudence<br/>Repository"]
    end

    subgraph "RAG Pipeline (Out of Scope)"
        Laws["Laws & Legislation"]
        JurisDB["Court Jurisprudence"]
        Norms["Internal Regulations"]
    end

    SA -->|"MCP: search_case"| CourtSys
    CA -->|"MCP: search_case"| CourtSys
    CA -->|"MCP: search_jurisprudence"| Juris
    SRA -->|"MCP: search_case, update_case"| CourtSys
    DA -->|"MCP: search_jurisprudence"| Juris

    Juris -.-> Laws
    Juris -.-> JurisDB
    Juris -.-> Norms

    style CourtSys fill:#f9d4a0,stroke:#e6a050
    style Juris fill:#f9d4a0,stroke:#e6a050
    style Laws fill:#d4d4d4,stroke:#999,stroke-dasharray: 5 5
    style JurisDB fill:#d4d4d4,stroke:#999,stroke-dasharray: 5 5
    style Norms fill:#d4d4d4,stroke:#999,stroke-dasharray: 5 5

    linkStyle 0,1,2,3,4 stroke:#1a73e8,stroke-width:2px
```

### Guardrails & Governance Layer

```mermaid
graph TD
    Input["Agent Input"] --> G1["1. RAG Grounding<br/>Official Sources Only"]
    G1 --> G2["2. Mandatory Citation<br/>Every response cites source"]
    G2 --> G3["3. Allowlist<br/>No open internet access"]
    G3 --> G4["4. Structured Output<br/>JSON validation"]
    G4 --> G5["5. Cross-Verification<br/>AuditAgent reviews other agents"]
    G5 --> G6["6. Template Adherence<br/>Strict format compliance"]
    G6 --> G7["7. Refusal Policy<br/>Insufficient data = explicit refusal"]
    G7 --> G8["8. Full Audit Trail<br/>Workflow state tracks everything"]
    G8 --> HG["Human Gate<br/>Magistrate validates & signs"]
    HG --> Output["Final Legal Act"]
```

## Data Model

Minimal data model for a prototype. Each agent returns a structured result that gets stored in the Workflow's `CaseState`.

### Enums

```java
public enum ProcedureType { ORDINARY, SUMMARY, FAST_TRACK }

public enum Urgency { LOW, MEDIUM, HIGH, URGENT }

public enum CaseStatus {
    RECEIVED, SCREENING, SECRETARIAT_PROCESSING, AUDITING,
    DRAFTING, AWAITING_HUMAN_APPROVAL, APPROVED, REJECTED, PUBLISHED
}
```

### Agent outputs

```java
public record ScreeningResult(
    ProcedureType procedureType,
    Urgency urgency,
    boolean documentsComplete,
    List<String> missingDocuments     // empty if complete
) {}

public record SecretariatResult(
    List<String> generatedActs       // e.g. "Subpoena for response", "File joining order"
) {}

public record AuditResult(
    boolean consistent,
    List<String> issues              // empty if consistent
) {}

public record DraftResult(
    String content,                  // the draft text
    List<String> citations           // RAG sources used
) {}
```

### Workflow state (single source of truth)

```java
public record CaseState(
    String caseNumber,               // Court System case identifier
    CaseStatus status,
    ScreeningResult screening,       // null until screening completes
    SecretariatResult secretariat,   // null until secretariat completes
    AuditResult audit,               // null until audit completes
    DraftResult draft,               // null until drafting completes
    String rejectionReason           // null unless rejected by magistrate
) {}
```

### View row types

Each View subscribes to `CaseProcessingWorkflow` state changes and projects a subset of `CaseState`.

```java
// CasesByQueueView - Magistrate's inbox
public record CaseQueueEntry(
    String caseNumber,
    CaseStatus status,
    ProcedureType procedureType,     // from screening
    Urgency urgency                  // from screening
) {}

// KPIDashboardView - Operational metrics
public record KPIEntry(
    String caseNumber,
    CaseStatus status,
    boolean documentsComplete,       // from screening
    boolean auditConsistent,         // from audit
    int auditIssueCount              // from audit
) {}

// AuditTrailView - Governance dashboard
public record AuditTrailEntry(
    String caseNumber,
    CaseStatus status,
    boolean hasScreening,            // screening != null
    boolean hasSecretariat,          // secretariat != null
    boolean hasAudit,                // audit != null
    boolean hasDraft,                // draft != null
    int citationCount                // from draft
) {}
```

## External Tools

Tools available to agents via MCP. **For the POC, all tools are stubs returning hardcoded data.** The RAG pipeline behind the Jurisprudence Repository is out of scope -- it lives behind the external service. Personal data masking is handled by Akka guardrails, not as an external tool.

```java
// Contract for court system integration
public interface CourtSystemTools {

    // Retrieves case documents and metadata from court system
    CaseDocuments searchCase(String caseNumber);

    // Publishes administrative acts (subpoenas, deadlines) back to court system
    void updateCase(String caseNumber, List<String> acts);
}

// Contract for legal knowledge base integration
public interface JurisprudenceTools {

    // Searches official legal databases (laws, jurisprudence, internal norms)
    // Returns grounded results with citations
    List<CitedSource> searchJurisprudence(String query);
}

public record CaseDocuments(
    String caseNumber,
    String content,                  // full case text
    List<String> attachedDocuments   // URLs to documents in court system
) {}

public record CitedSource(
    String content,                  // relevant excerpt
    String source                    // e.g. "Legal Code Art. 477", "Court Precedent 331"
) {}
```

### Tool access per agent

| Agent | CourtSystemTools | JurisprudenceTools |
|---|---|---|
| `ScreeningAgent` | `searchCase` | |
| `ConsistencyAuditAgent` | `searchCase` | `searchJurisprudence` |
| `SecretariatRoutineAgent` | `searchCase`, `updateCase` | |
| `DraftingSupportAgent` | | `searchJurisprudence` |

### Agent prompts

```
ScreeningAgent:
  "You are a court screening clerk. Given a case number, use the
   searchCase MCP tool to retrieve the case data. Then classify:
   1. The procedure type (ORDINARY, SUMMARY, or FAST_TRACK)
   2. The urgency level (LOW, MEDIUM, HIGH, or URGENT)
   3. Whether all required documents are present
   If documents are missing, list them.
   Respond with a ScreeningResult."

ConsistencyAuditAgent:
  "You are a court auditor. Given a case number, use the searchCase
   MCP tool to retrieve case data and the searchJurisprudence MCP tool to
   validate against legal norms. Verify formal consistency:
   - Dates are valid and not contradictory
   - Claimed values match supporting documents
   - The request is legally coherent
   If you find issues, list each one. Respond with an AuditResult."

SecretariatRoutineAgent:
  "You are a court secretariat assistant. Given a case number, use
   the searchCase MCP tool to retrieve case data. Generate the required
   administrative acts (subpoenas, deadline notifications, file joining orders).
   Then use the updateCase MCP tool to publish the acts to the court system.
   Respond with a SecretariatResult."

DraftingSupportAgent:
  "You are a court drafting assistant. Given a case and its audit
   results, use the searchJurisprudence MCP tool to find relevant
   precedents. Draft a decision suggestion based ONLY on retrieved
   jurisprudence. Every statement must cite its source. If insufficient
   legal basis exists, explicitly state that rather than inventing content.
   Respond with a DraftResult."
```

## Akka SDK Components

| Component | Type | Purpose |
|---|---|---|
| `CaseProcessingWorkflow` | Workflow | Single source of truth. Orchestrates Screening -> Secretariat -> Audit -> Drafting -> Human Approval. `CaseState` holds all verifications, RAG evidence, and approvals. |
| `ScreeningAgent` | Agent | Classifies rite, urgency, document completeness |
| `ConsistencyAuditAgent` | Agent | Detects inconsistencies before magistrate review |
| `SecretariatRoutineAgent` | Agent | Automates subpoenas, deadline checks, file joining |
| `DraftingSupportAgent` | Agent | Generates drafts grounded in jurisprudence via RAG |
| `CasesByQueueView` | View | Queue management by priority and status (subscribes to Workflow) |
| `KPIDashboardView` | View | Operational metrics: triage time, rework rate, etc. (subscribes to Workflow) |
| `AuditTrailView` | View | Governance and compliance dashboard (subscribes to Workflow) |
| `CaseEndpoint` | HTTP Endpoint | REST API for human interaction and approvals |
| `CourtToolsMcpEndpoint` | MCP Endpoint | MCP tools for Word/external integrations |
| `CourtEventConsumer` | Consumer | Ingests events from court system |
| `ApprovalNotificationConsumer` | Consumer | Subscribes to Workflow, pushes to message broker when case reaches AWAITING_APPROVAL |