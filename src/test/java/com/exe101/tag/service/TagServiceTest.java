package com.exe101.tag.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Tag;
import com.exe101.tag.mapper.TagEntityMapper;
import com.exe101.tag.model.NewTag;
import com.exe101.tag.model.TagChange;
import com.exe101.tag.repository.TagRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {
    @Mock
    private TagRepository repository;
    @Spy
    private TagEntityMapper mapper = Mappers.getMapper(TagEntityMapper.class);
    @InjectMocks
    private TagServiceImpl service;

    @Test
    void create_tag_ok() {
        // given
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            Tag entity = invocation.getArgument(0);
            entity.setTagId(5);
            return entity;
        });

        // when
        var result = service.create(new NewTag(" Landscape "));
        var captured = ArgumentCaptor.forClass(Tag.class);
        verify(repository).saveAndFlush(captured.capture());

        // then
        assertAll(
            () -> assertThat(result.getTagId()).isEqualTo(5),
            () -> assertThat(result.isActive()).isTrue(),
            () -> assertThat(captured.getValue().getName()).isEqualTo("Landscape"),
            () -> assertThat(captured.getValue().isIsActive()).isTrue()
        );
    }

    @Test
    void create_tag_duplicate_name_ko() {
        // given
        when(repository.existsByName("Landscape")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.create(new NewTag("Landscape")))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.TAG_ALREADY_EXISTS.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deactivate_tag_ok() {
        // given
        var existing = tag(5, "Landscape", true);
        when(repository.findById(5)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.update(5, new TagChange("Landscape", false));

        // then
        assertAll(
            () -> assertThat(result.isActive()).isFalse(),
            () -> assertThat(existing.isIsActive()).isFalse(),
            () -> verify(repository, never()).delete(any())
        );
    }

    @Test
    void deactivate_tag_not_found_ko() {
        // given
        when(repository.findById(5)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.update(5, new TagChange("Landscape", false)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.TAG_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reactivate_tag_ok() {
        // given
        var existing = tag(5, "Landscape", false);
        when(repository.findById(5)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.update(5, new TagChange("Landscapes", true));

        // then
        assertAll(
            () -> assertThat(result.isActive()).isTrue(),
            () -> assertThat(result.getName()).isEqualTo("Landscapes")
        );
    }

    private Tag tag(Integer tagId, String name, boolean active) {
        var tag = new Tag(name, active);
        tag.setTagId(tagId);

        return tag;
    }
}
