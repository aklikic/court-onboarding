package com.example.domain;

import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.FunctionTool;

import java.util.List;

public interface CourtSystemService {

    @FunctionTool(description = "Retrieves case documents and metadata from the court system for a given case number.")
    CaseDocuments searchCase(@Description("The court case number to search for.") String caseNumber);

    @FunctionTool(description = "Publishes administrative acts (subpoenas, deadline notifications, file joining orders) back to the court system.")
    void updateCase(
        @Description("The court case number to update.") String caseNumber,
        @Description("List of administrative acts to publish.") List<String> acts);
}