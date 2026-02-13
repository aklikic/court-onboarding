package com.example.domain;

import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.FunctionTool;

import java.util.List;

public interface JurisprudenceService {

    @FunctionTool(description = "Searches official legal databases (laws, jurisprudence, internal norms) and returns grounded results with citations.")
    List<CitedSource> searchJurisprudence(@Description("The legal query to search for.") String query);
}