package com.example;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import com.example.application.CourtSystemServiceStub;
import com.example.application.JurisprudenceServiceStub;
import com.example.domain.CourtSystemService;
import com.example.domain.JurisprudenceService;

@Setup
public class Bootstrap implements ServiceSetup {

    @Override
    public DependencyProvider createDependencyProvider() {
        final var courtSystemService = new CourtSystemServiceStub();
        final var jurisprudenceService = new JurisprudenceServiceStub();

        return new DependencyProvider() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T getDependency(Class<T> clazz) {
                if (clazz == CourtSystemService.class) {
                    return (T) courtSystemService;
                } else if (clazz == JurisprudenceService.class) {
                    return (T) jurisprudenceService;
                } else {
                    throw new IllegalArgumentException("Unknown dependency type: " + clazz);
                }
            }
        };
    }
}