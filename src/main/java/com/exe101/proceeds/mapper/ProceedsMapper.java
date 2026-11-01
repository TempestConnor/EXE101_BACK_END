package com.exe101.proceeds.mapper;

import com.exe101.proceeds.dto.ProceedsDTO;
import com.exe101.proceeds.dto.RecordReleaseOutcomeDTO;
import com.exe101.proceeds.dto.ReleaseDTO;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.Release;
import com.exe101.proceeds.model.ReleaseOutcome;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProceedsMapper {
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "transferReference", target = "withTransferReference")
    @Mapping(source = "reason", target = "withReason")
    ReleaseOutcome toModel(RecordReleaseOutcomeDTO dto);

    ProceedsDTO toDto(Proceeds proceeds);

    List<ProceedsDTO> toProceedsDto(List<Proceeds> proceeds);

    ReleaseDTO toDto(Release release);

    List<ReleaseDTO> toReleaseDto(List<Release> releases);
}
