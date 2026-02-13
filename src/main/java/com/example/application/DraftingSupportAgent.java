package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import com.example.domain.DraftResult;
import com.example.domain.JurisprudenceService;

@Component(id = "drafting-support-agent")
public class DraftingSupportAgent extends Agent {

    private static final String SYSTEM_MESSAGE = """
        You are a court drafting assistant. Given a case and its audit
        results, use the searchJurisprudence tool to find relevant
        precedents. Draft a decision suggestion based ONLY on retrieved
        jurisprudence. Every statement must cite its source. If insufficient
        legal basis exists, explicitly state that rather than inventing content.

        Respond ONLY with a JSON object in this exact format, no other text:
        {
          "content": "The draft decision text here...",
          "citations": ["Civil Code Art. 927", "Court Precedent STJ-331/2024"]
        }
        """.stripIndent();

    private final JurisprudenceService jurisprudenceService;

    public DraftingSupportAgent(JurisprudenceService jurisprudenceService) {
        this.jurisprudenceService = jurisprudenceService;
    }

    public record DraftRequest(String caseNumber, String auditSummary) {}

    public Effect<DraftResult> process(DraftRequest request) {
        return effects()
            .systemMessage(SYSTEM_MESSAGE)
            .tools(jurisprudenceService)
            .userMessage("Draft decision for case %s. Audit summary: %s".formatted(
                request.caseNumber(), request.auditSummary()))
            .responseAs(DraftResult.class)
            .thenReply();
    }
}