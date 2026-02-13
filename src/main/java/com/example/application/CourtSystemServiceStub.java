package com.example.application;

import com.example.domain.CaseDocuments;
import com.example.domain.CourtSystemService;

import java.util.ArrayList;
import java.util.List;

public class CourtSystemServiceStub implements CourtSystemService {

    private final List<String> publishedActs = new ArrayList<>();

    @Override
    public CaseDocuments searchCase(String caseNumber) {
        return new CaseDocuments(
            caseNumber,
            """
            Case %s - Civil Liability Claim
            Plaintiff: Maria Silva
            Defendant: Auto Insurance Corp.
            Filed: 2024-11-15
            Subject: Claim for damages arising from traffic accident on 2024-08-20.
            Plaintiff alleges property damage of R$ 25,000 and moral damages of R$ 10,000.
            Defendant was notified on 2024-11-20.
            Response deadline: 2024-12-05.
            Attached: police report, medical report, vehicle repair estimate, insurance policy.
            """.formatted(caseNumber),
            List.of(
                "police_report_2024_08_20.pdf",
                "medical_report_maria_silva.pdf",
                "vehicle_repair_estimate.pdf",
                "insurance_policy_auto_corp.pdf"
            )
        );
    }

    @Override
    public void updateCase(String caseNumber, List<String> acts) {
        publishedActs.addAll(acts);
    }

    public List<String> getPublishedActs() {
        return List.copyOf(publishedActs);
    }
}