package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import com.example.domain.AuditResult;
import com.example.domain.CourtSystemService;
import com.example.domain.JurisprudenceService;

@Component(id = "consistency-audit-agent")
public class ConsistencyAuditAgent extends Agent {

    private static final String SYSTEM_MESSAGE = """
        You are a court auditor. Given a case number, use the searchCase
        tool to retrieve case data and the searchJurisprudence tool to
        validate against legal norms. Verify formal consistency:
        - Dates are valid and not contradictory
        - Claimed values match supporting documents
        - The request is legally coherent
        If you find issues, list each one.

        Respond ONLY with a JSON object in this exact format, no other text:
        {
          "consistent": true,
          "issues": []
        }
        """.stripIndent();

    private final CourtSystemService courtSystemService;
    private final JurisprudenceService jurisprudenceService;

    public ConsistencyAuditAgent(CourtSystemService courtSystemService,
                                  JurisprudenceService jurisprudenceService) {
        this.courtSystemService = courtSystemService;
        this.jurisprudenceService = jurisprudenceService;
    }

    public Effect<AuditResult> process(String caseNumber) {
        return effects()
            .systemMessage(SYSTEM_MESSAGE)
            .tools(courtSystemService, jurisprudenceService)
            .userMessage("Audit case number: " + caseNumber)
            .responseAs(AuditResult.class)
            .thenReply();
    }
}