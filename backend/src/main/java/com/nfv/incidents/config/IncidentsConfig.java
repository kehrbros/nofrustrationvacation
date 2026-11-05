package com.nfv.incidents.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AssessmentProperties.class)
public class IncidentsConfig {
}
