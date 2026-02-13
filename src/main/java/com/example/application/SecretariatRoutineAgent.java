package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import com.example.domain.CourtSystemService;
import com.example.domain.SecretariatResult;

@Component(id = "secretariat-routine-agent")
public class SecretariatRoutineAgent extends Agent {

    private static final String SYSTEM_MESSAGE = """
        You are a court secretariat assistant. Given a case number, use
        the searchCase tool to retrieve case data. Based on the case data,
        determine which administrative acts are needed (subpoenas, deadline
        notifications, file joining orders).

        Respond ONLY with a JSON object in this exact format, no other text:
        {
          "generatedActs": ["Subpoena for response", "Deadline notification"]
        }
        """.stripIndent();

    private final CourtSystemService courtSystemService;

    public SecretariatRoutineAgent(CourtSystemService courtSystemService) {
        this.courtSystemService = courtSystemService;
    }

    public Effect<SecretariatResult> process(String caseNumber) {
        return effects()
            .systemMessage(SYSTEM_MESSAGE)
            .tools(courtSystemService)
            .userMessage("Process case number: " + caseNumber)
            .responseAs(SecretariatResult.class)
            .thenReply();
    }
}