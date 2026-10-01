package com.github.shikejing.reportenginejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReportEngineProperties.class)
public class ReportEngineConfiguration {
}
