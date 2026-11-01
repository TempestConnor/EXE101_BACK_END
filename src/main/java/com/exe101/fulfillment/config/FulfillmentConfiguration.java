package com.exe101.fulfillment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FulfillmentProperties.class)
public class FulfillmentConfiguration {
}
