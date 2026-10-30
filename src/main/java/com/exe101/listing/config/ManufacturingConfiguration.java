package com.exe101.listing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ManufacturingProperties.class)
public class ManufacturingConfiguration {
}
