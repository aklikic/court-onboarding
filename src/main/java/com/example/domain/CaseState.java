package com.example.domain;

public record CaseState(
    String caseNumber,
    CaseStatus status,
    ScreeningResult screening,
    SecretariatResult secretariat,
    AuditResult audit,
    DraftResult draft,
    String rejectionReason,
    String failureMessage
) {

    public static CaseState create(String caseNumber) {
        return new CaseState(caseNumber, CaseStatus.RECEIVED, null, null, null, null, null, null);
    }

    public CaseState withStatus(CaseStatus newStatus) {
        return new CaseState(caseNumber, newStatus, screening, secretariat, audit, draft, rejectionReason, failureMessage);
    }

    public CaseState withScreening(ScreeningResult result) {
        return new CaseState(caseNumber, CaseStatus.SCREENING_COMPLETE, result, secretariat, audit, draft, rejectionReason, failureMessage);
    }

    public CaseState withSecretariat(SecretariatResult result) {
        return new CaseState(caseNumber, CaseStatus.SECRETARIAT_COMPLETE, screening, result, audit, draft, rejectionReason, failureMessage);
    }

    public CaseState withAuditPassed(AuditResult result) {
        return new CaseState(caseNumber, CaseStatus.AUDIT_PASSED, screening, secretariat, result, draft, rejectionReason, failureMessage);
    }

    public CaseState withAuditFailed(AuditResult result) {
        return new CaseState(caseNumber, CaseStatus.AUDIT_FAILED, screening, secretariat, result, draft, rejectionReason, failureMessage);
    }

    public CaseState withDraft(DraftResult result) {
        return new CaseState(caseNumber, CaseStatus.DRAFT_READY, screening, secretariat, audit, result, rejectionReason, failureMessage);
    }

    public CaseState withRejection(String reason) {
        return new CaseState(caseNumber, CaseStatus.REJECTED, screening, secretariat, audit, draft, reason, failureMessage);
    }

    public CaseState withFailure(String message) {
        return new CaseState(caseNumber, CaseStatus.FAILED, screening, secretariat, audit, draft, rejectionReason, message);
    }
}