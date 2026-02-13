package com.example.domain;

import java.util.List;

public record DraftResult(
    String content,
    List<String> citations
) {}