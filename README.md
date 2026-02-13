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

    ScreeningAgent -.->|"@FunctionTool: searchCase"| CourtSys
    AuditAgent -.->|"@FunctionTool: searchCase"| CourtSys
    AuditAgent -.->|"@FunctionTool: searchJurisprudence"| Juris
    DraftingAgent -.->|"@FunctionTool: searchJurisprudence"| Juris
    SecretariatAgent -.->|"@FunctionTool: searchCase"| CourtSys

    CaseWorkflow -->|state changes| QueueView
    CaseWorkflow -->|state changes| KPIView
    CaseWorkflow -->|state changes| AuditView
    CaseWorkflow -->|state changes| ApprovalConsumer
    ApprovalConsumer -->|"push when AWAITING_APPROVAL"| Topic
    Topic -->|notification| Magistrate

    Magistrate -->|"1. GET /cases/queue<br/>(SSE streaming inbox)"| HTTP
    HTTP -->|query| QueueView
    HTTP -->|query| KPIView
    HTTP -->|query| AuditView
    Magistrate -->|"2. GET /cases/{id}<br/>(review draft + evidence)"| HTTP
    Magistrate -->|"3. POST /cases/{id}/approve<br/>or /reject or /continue or /fail"| HTTP
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

When the Workflow reaches `AWAITING_HUMAN_APPROVAL`, it pauses and waits for an external command. The `CasesByQueueView` streams real-time updates via SSE to the dashboard UI, acting as the Magistrate's inbox. The Magistrate reviews case details and approves/rejects via `CaseEndpoint`, which sends a command back to the Workflow. Audit failures and step failures also pause for human intervention.

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

    state "AUDIT_FAILED" as AUDIT_FAILED
    note right of AUDIT_FAILED
        Workflow pauses for human review.
        Magistrate can:
        - POST /cases/{id}/continue (proceed to drafting)
        - POST /cases/{id}/fail (mark as failed)
    end note

    AUDIT_FAILED --> DRAFTING: continue command
    AUDIT_FAILED --> FAILED: fail command

    AUDIT_PASSED --> DRAFTING: startDraftingStep
    DRAFTING --> DRAFT_READY: DraftingSupportAgent<br/>creates draft via RAG

    DRAFT_READY --> AWAITING_HUMAN_APPROVAL: step ends,<br/>workflow pauses

    state "AWAITING_HUMAN_APPROVAL" as AWAITING_HUMAN_APPROVAL
    note right of AWAITING_HUMAN_APPROVAL
        Workflow pauses for human decision.
        Magistrate uses CaseEndpoint:
        1. GET /cases/queue (SSE streaming inbox)
        2. GET /cases/{id} (review draft + RAG citations)
        3. POST /cases/{id}/approve or /reject
    end note

    AWAITING_HUMAN_APPROVAL --> APPROVED: approve command<br/>via CaseEndpoint
    AWAITING_HUMAN_APPROVAL --> REJECTED: reject command<br/>via CaseEndpoint

    REJECTED --> DRAFTING: revise draft

    APPROVED --> PUBLISHED: publishStep<br/>push to Court System
    PUBLISHED --> [*]

    state "FAILED" as FAILED
    note right of FAILED
        Step failed after retries or
        manually failed by magistrate.
        POST /cases/{id}/resume to retry.
    end note
    FAILED --> RECEIVED: resume command
```

### Akka Component Detail Map

```mermaid
graph LR
    subgraph "domain package"
        CaseState["CaseState<br/>(workflow state record)"]
        ScreeningResult["ScreeningResult"]
        AuditResult["AuditResult"]
        DraftResult["DraftResult"]
        SecretariatResult["SecretariatResult"]
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
    DA -->|returns| DraftResult
    SRA -->|returns| SecretariatResult
    QV -->|"@Consume.FromWorkflow"| Workflow
    KV -->|"@Consume.FromWorkflow"| Workflow
    AV -->|"@Consume.FromWorkflow"| Workflow
    EP -->|queries| QV
    EP -->|queries| KV
    EP -->|queries| AV
    EP -->|commands| Workflow
```

### Agent Tool Integration (@FunctionTool)

Each agent connects to external systems via `@FunctionTool`-annotated service interfaces injected as tools. For the POC, tools are implemented as **stubs** returning hardcoded data. The RAG pipeline behind the Jurisprudence Repository is out of scope.

```mermaid
graph TB
    subgraph "AI Agents"
        SA["ScreeningAgent"]
        CA["ConsistencyAuditAgent"]
        SRA["SecretariatRoutineAgent"]
        DA["DraftingSupportAgent"]
    end

    subgraph "Function Tools (Stubs for POC)"
        CourtSys["CourtSystemService<br/>(Case Database)"]
        Juris["JurisprudenceService<br/>(Legal Repository)"]
    end

    subgraph "RAG Pipeline (Out of Scope)"
        Laws["Laws & Legislation"]
        JurisDB["Court Jurisprudence"]
        Norms["Internal Regulations"]
    end

    SA -->|"searchCase"| CourtSys
    CA -->|"searchCase"| CourtSys
    CA -->|"searchJurisprudence"| Juris
    SRA -->|"searchCase"| CourtSys
    DA -->|"searchJurisprudence"| Juris

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
    RECEIVED, SCREENING, SCREENING_COMPLETE,
    SECRETARIAT_PROCESSING, SECRETARIAT_COMPLETE,
    AUDITING, AUDIT_PASSED, AUDIT_FAILED,
    DRAFTING, DRAFT_READY,
    AWAITING_HUMAN_APPROVAL, APPROVED, REJECTED,
    PUBLISHED, FAILED
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
    String rejectionReason,          // null unless rejected by magistrate
    String failureMessage            // null unless workflow step failed
) {}
```

### View row types

Each View subscribes to `CaseProcessingWorkflow` state changes and projects a subset of `CaseState`.

```java
// CasesByQueueView - Magistrate's inbox (streamed via SSE)
public record CaseQueueEntry(
    String caseNumber,
    String status,
    String procedureType,            // from screening
    String urgency,                  // from screening
    String failureMessage,           // from workflow failure
    String auditIssues               // from audit (joined with ";")
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

Tools available to agents via `@FunctionTool`-annotated service interfaces. **For the POC, all tools are stubs returning hardcoded data.** The RAG pipeline behind the Jurisprudence Repository is out of scope -- it lives behind the external service. Personal data masking is handled by Akka guardrails, not as an external tool.

```java
// Contract for court system integration
public interface CourtSystemService {

    @FunctionTool(description = "Retrieves case documents and metadata from the court system")
    CaseDocuments searchCase(String caseNumber);

    @FunctionTool(description = "Publishes administrative acts back to the court system")
    void updateCase(String caseNumber, List<String> acts);
}

// Contract for legal knowledge base integration
public interface JurisprudenceService {

    @FunctionTool(description = "Searches official legal databases")
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

| Agent | CourtSystemService | JurisprudenceService |
|---|---|---|
| `ScreeningAgent` | `searchCase` | |
| `ConsistencyAuditAgent` | `searchCase` | `searchJurisprudence` |
| `SecretariatRoutineAgent` | `searchCase` | |
| `DraftingSupportAgent` | | `searchJurisprudence` |

### Agent prompts

```
ScreeningAgent:
  "You are a court screening clerk. Given a case number, use the
   searchCase tool to retrieve the case data. Then classify:
   1. The procedure type (ORDINARY, SUMMARY, or FAST_TRACK)
   2. The urgency level (LOW, MEDIUM, HIGH, or URGENT)
   3. Whether all required documents are present
   If documents are missing, list them.
   Respond with a ScreeningResult."

ConsistencyAuditAgent:
  "You are a court auditor. Given a case number, use the searchCase
   tool to retrieve case data and the searchJurisprudence tool to
   validate against legal norms. Verify formal consistency:
   - Dates are valid and not contradictory
   - Claimed values match supporting documents
   - The request is legally coherent
   If you find issues, list each one. Respond with an AuditResult."

SecretariatRoutineAgent:
  "You are a court secretariat assistant. Given a case number, use
   the searchCase tool to retrieve case data. Based on the case data,
   determine which administrative acts are needed (subpoenas, deadline
   notifications, file joining orders).
   Respond with a SecretariatResult."

DraftingSupportAgent:
  "You are a court drafting assistant. Given a case and its audit
   results, use the searchJurisprudence tool to find relevant
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
| `CasesByQueueView` | View | Queue management with SSE streaming updates (subscribes to Workflow) |
| `KPIDashboardView` | View | Operational metrics: triage time, rework rate, etc. (subscribes to Workflow) |
| `AuditTrailView` | View | Governance and compliance dashboard (subscribes to Workflow) |
| `CaseEndpoint` | HTTP Endpoint | REST API for human interaction, approvals, and SSE streaming queue |
| `DashboardEndpoint` | HTTP Endpoint | Serves the single-page dashboard UI at `/` |
| `CourtToolsMcpEndpoint` | MCP Endpoint | Tools for Word/external integrations |
| `CourtEventConsumer` | Consumer | Ingests events from court system |
| `ApprovalNotificationConsumer` | Consumer | Subscribes to Workflow, pushes to message broker when case reaches AWAITING_APPROVAL |

## API Endpoints (curl examples)

### Start a new case

```shell
curl -X POST http://localhost:9000/cases/case-001/start \
  -H 'Content-Type: application/json' \
  -d '{"caseNumber": "CASE-2024-001"}'
```

### Get case state

```shell
curl http://localhost:9000/cases/case-001
```

### Approve a case (when AWAITING_HUMAN_APPROVAL)

```shell
curl -X POST http://localhost:9000/cases/case-001/approve
```

### Reject a case (when AWAITING_HUMAN_APPROVAL)

```shell
curl -X POST http://localhost:9000/cases/case-001/reject \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Insufficient evidence in supporting documents"}'
```

### Resume a failed case

```shell
curl -X POST http://localhost:9000/cases/case-001/resume
```

### Continue from audit failure (when AUDIT_FAILED)

```shell
curl -X POST http://localhost:9000/cases/case-001/continue
```

### Fail a case

```shell
curl -X POST http://localhost:9000/cases/case-001/fail \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Audit issues unresolvable"}'
```

### Stream live workflow updates (SSE)

```shell
curl -N http://localhost:9000/cases/case-001/updates
```

### Stream all cases queue (SSE with real-time updates)

```shell
curl -N http://localhost:9000/cases/queue
```

### Get cases by status

```shell
curl http://localhost:9000/cases/queue/AWAITING_HUMAN_APPROVAL
```

### Get full audit trail

```shell
curl http://localhost:9000/cases/audit-trail
```

### Get audit trail for a specific case

```shell
curl http://localhost:9000/cases/audit-trail/CASE-2024-001
```

### Get KPI dashboard

```shell
curl http://localhost:9000/cases/kpi
```

### Get cases with incomplete documents

```shell
curl http://localhost:9000/cases/kpi/incomplete-documents
```

### Get cases with failed audits

```shell
curl http://localhost:9000/cases/kpi/failed-audits
```

## Build, Run & Deploy

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker
- [Akka CLI](https://doc.akka.io/reference/cli/index.html) (`akka`)

### Run locally

```shell
GOOGLE_AI_GEMINI_API_KEY=your-key-here mvn compile exec:java
```

The dashboard UI is available at `http://localhost:9000/`.

### Run tests

```shell
mvn verify
```

### Build Docker image

```shell
mvn clean install -DskipTests
```

This builds the Docker image `court-onboarding:1.0-SNAPSHOT` via the Akka SDK parent pom.

### Tag and push

Replace `your-registry` with your Docker registry (e.g. Docker Hub username or GCR/ECR path):

```shell
export VERSION=$(date +%Y%m%d%H%M%S)
docker tag court-onboarding:1.0-SNAPSHOT your-registry/court-onboarding:1.0-SNAPSHOT-$VERSION
docker push your-registry/court-onboarding:1.0-SNAPSHOT-$VERSION
```

### Deploy to Akka

#### 1. Create the secret for the Gemini API key

```shell
akka secrets create app-secret \
  --secret-key-value GOOGLE_AI_GEMINI_API_KEY=your-key-here
```

#### 2. Update `service.yaml` image reference

Edit `service.yaml` to point to your pushed image:

```yaml
name: court-onboarding
service:
  env:
    - name: GOOGLE_AI_GEMINI_API_KEY
      valueFrom:
        secretKeyRef:
          name: app-secret
          key: GOOGLE_AI_GEMINI_API_KEY
  image: your-registry/court-onboarding:1.0-SNAPSHOT-20260213165326
  resources:
    runtime:
      mode: embedded
```

#### 3. Deploy the service

```shell
akka service deploy court-onboarding service.yaml
```

#### 4. Check status

```shell
akka service list
akka service logs court-onboarding
```

#### 5. Expose publicly (optional)

```shell
akka service expose court-onboarding --enable-cors
```