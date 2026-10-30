package com.exe101.audit.service;

import com.exe101.audit.mapper.AuditEntityMapper;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.repository.AuditRepository;
import com.exe101.entity.AdminAudit;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// MANDATORY: an audit record commits with the audited action or not at all.
@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final AuditRepository repository;
    private final AuditEntityMapper mapper;

    @Override
    public void record(AuditEntry entry) {
        AdminAudit entity = mapper.toEntity(entry);
        entity.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        repository.saveAndFlush(entity);
    }
}
