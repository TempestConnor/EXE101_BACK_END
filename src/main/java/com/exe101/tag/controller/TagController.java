package com.exe101.tag.controller;

import com.exe101.tag.dto.CreateTagDTO;
import com.exe101.tag.dto.TagDTO;
import com.exe101.tag.dto.UpdateTagDTO;
import com.exe101.tag.mapper.TagMapper;
import com.exe101.tag.service.TagService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;
    private final TagMapper mapper;

    @GetMapping
    public List<TagDTO> listAll() {
        var tags = tagService.listAll();
        var response = mapper.toDto(tags);

        return response;
    }

    @GetMapping("/active")
    public List<TagDTO> listActive() {
        var tags = tagService.listActive();
        var response = mapper.toDto(tags);

        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagDTO create(@RequestBody @Valid CreateTagDTO dto) {
        var tag = mapper.toModel(dto);
        var created = tagService.create(tag);
        var response = mapper.toDto(created);

        return response;
    }

    @PutMapping("/{tagId}")
    public TagDTO update(@PathVariable Integer tagId, @RequestBody @Valid UpdateTagDTO dto) {
        var change = mapper.toModel(dto);
        var updated = tagService.update(tagId, change);
        var response = mapper.toDto(updated);

        return response;
    }
}
