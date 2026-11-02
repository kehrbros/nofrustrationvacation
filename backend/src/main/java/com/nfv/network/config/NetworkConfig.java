package com.nfv.network.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NetworkProperties.class)
public class NetworkConfig {
}