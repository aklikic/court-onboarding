package com.example.domain;

import java.util.List;

public record AuditResult(
    boolean consistent,
    List<String> issues
) {}