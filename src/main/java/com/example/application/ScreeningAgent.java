package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import com.example.domain.CourtSystemService;
import com.example.domain.ScreeningResult;

@Component(id = "screening-agent")
public class ScreeningAgent extends Agent {

    private static final String SYSTEM_MESSAGE = """
        You are a court screening clerk. Given a case number, use the
        searchCase tool to retrieve the case data. Then classify:
        1. The procedure type (ORDINARY, SUMMARY, or FAST_TRACK)
        2. The urgency level (LOW, MEDIUM, HIGH, or URGENT)
        3. Whether all required documents are present
        If documents are missing, list them.

        Respond ONLY with a JSON object in this exact format, no other text:
        {
          "procedureType": "ORDINARY",
          "urgency": "MEDIUM",
          "documentsComplete": true,
          "missingDocuments": []
        }
        Enum values must be plain strings, not objects.
        """.stripIndent();

    private final CourtSystemService courtSystemService;

    public ScreeningAgent(CourtSystemService courtSystemService) {
        this.courtSystemService = courtSystemService;
    }

    public Effect<ScreeningResult> process(String caseNumber) {
        return effects()
            .systemMessage(SYSTEM_MESSAGE)
            .tools(courtSystemService)
            .userMessage("Screen case number: " + caseNumber)
            .responseAs(ScreeningResult.class)
            .thenReply();
    }
}