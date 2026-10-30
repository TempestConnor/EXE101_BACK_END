package com.exe101.audit.service;

import com.exe101.audit.model.AuditEntry;

public interface AuditService {
    void record(AuditEntry entry);
}
