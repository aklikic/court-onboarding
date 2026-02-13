package com.example.domain;

import java.util.List;

public record ScreeningResult(
    ProcedureType procedureType,
    Urgency urgency,
    boolean documentsComplete,
    List<String> missingDocuments
) {}