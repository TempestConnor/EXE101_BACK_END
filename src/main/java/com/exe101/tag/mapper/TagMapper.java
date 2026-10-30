package com.exe101.tag.mapper;

import com.exe101.tag.dto.CreateTagDTO;
import com.exe101.tag.dto.TagDTO;
import com.exe101.tag.dto.UpdateTagDTO;
import com.exe101.tag.model.NewTag;
import com.exe101.tag.model.Tag;
import com.exe101.tag.model.TagChange;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TagMapper {
    @Mapping(source = "name", target = "withName")
    NewTag toModel(CreateTagDTO dto);

    @Mapping(source = "name", target = "withName")
    @Mapping(source = "active", target = "withActive")
    TagChange toModel(UpdateTagDTO dto);

    TagDTO toDto(Tag tag);

    List<TagDTO> toDto(List<Tag> tags);
}
