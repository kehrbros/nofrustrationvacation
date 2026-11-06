package com.nfv.advisor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdvisorProperties.class)
public class AdvisorConfig {
}
