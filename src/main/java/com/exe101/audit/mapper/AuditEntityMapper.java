package com.exe101.audit.mapper;

import com.exe101.audit.model.AuditEntry;
import com.exe101.entity.AdminAudit;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuditEntityMapper {
    @Mapping(target = "auditId", ignore = true)
    @Mapping(target = "occurredAt", ignore = true)
    AdminAudit toEntity(AuditEntry entry);
}
