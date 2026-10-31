package com.exe101.file.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// orphanMinAge keeps the sweep away from uploads whose transaction has not committed yet.
@ConfigurationProperties("app.storage")
public record StorageProperties(String root, Duration orphanMinAge) {
}
