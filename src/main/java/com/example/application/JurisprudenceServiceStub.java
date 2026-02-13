package com.example.application;

import com.example.domain.CitedSource;
import com.example.domain.JurisprudenceService;

import java.util.List;

public class JurisprudenceServiceStub implements JurisprudenceService {

    @Override
    public List<CitedSource> searchJurisprudence(String query) {
        return List.of(
            new CitedSource(
                "The party causing damage through an unlawful act is obligated to repair it. " +
                "The obligation to repair arises regardless of fault in cases specified by law.",
                "Civil Code Art. 927"
            ),
            new CitedSource(
                "Moral damages arising from traffic accidents are presumed when bodily injury is proven. " +
                "Compensation should be set at a level that is both proportional and dissuasive.",
                "Court Precedent STJ-331/2024"
            ),
            new CitedSource(
                "The insurer is directly liable to the injured third party up to the policy limit. " +
                "The insurance policy constitutes a guarantee to the victim.",
                "Insurance Regulatory Norm SUSEP-42"
            )
        );
    }
}