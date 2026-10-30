package com.exe101.tag.mapper;

import com.exe101.tag.model.Tag;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TagEntityMapper {
    @Mapping(source = "tagId", target = "withTagId")
    @Mapping(source = "name", target = "withName")
    @Mapping(source = "isActive", target = "withActive")
    Tag toModel(com.exe101.entity.Tag entity);

    List<Tag> toModel(List<com.exe101.entity.Tag> entities);
}
