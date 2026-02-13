package com.example.domain;

import java.util.List;

public record CaseDocuments(
    String caseNumber,
    String content,
    List<String> attachedDocuments
) {}