package com.exe101.tag.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.tag.mapper.TagEntityMapper;
import com.exe101.tag.model.NewTag;
import com.exe101.tag.model.Tag;
import com.exe101.tag.model.TagChange;
import com.exe101.tag.repository.TagRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {
    private static int maxNameLength = 100;
    private final TagRepository repository;
    private final TagEntityMapper mapper;

    @Override
    public Tag create(NewTag tag) {
        String name = normalizeName(tag.getName());

        if (repository.existsByName(name)) {
            throw new AppException(AppErrorMessage.TAG_ALREADY_EXISTS);
        }

        com.exe101.entity.Tag saved = save(new com.exe101.entity.Tag(name, true));
        log.info("[TAG] - ACTION: create: tagId: {}", saved.getTagId());

        return mapper.toModel(saved);
    }

    @Override
    public Tag update(Integer tagId, TagChange change) {
        com.exe101.entity.Tag entity = repository.findById(tagId)
            .orElseThrow(() -> new AppException(AppErrorMessage.TAG_NOT_FOUND));
        String name = normalizeName(change.getName());

        if (repository.existsByNameAndTagIdNot(name, tagId)) {
            throw new AppException(AppErrorMessage.TAG_ALREADY_EXISTS);
        }

        entity.setName(name);
        entity.setIsActive(change.isActive());
        com.exe101.entity.Tag saved = save(entity);
        log.info("[TAG] - ACTION: update: tagId: {}: active: {}", tagId, saved.isIsActive());

        return mapper.toModel(saved);
    }

    @Override
    public List<Tag> listAll() {
        return mapper.toModel(repository.findAllByOrderByNameAsc());
    }

    @Override
    public List<Tag> listActive() {
        return mapper.toModel(repository.findByIsActiveTrueOrderByNameAsc());
    }

    @Override
    public List<Tag> requireActive(Collection<Integer> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }

        Set<Integer> requested = new HashSet<>(tagIds);
        List<Tag> found = findActive(requested);

        if (found.size() != requested.size()) {
            throw new AppException(AppErrorMessage.TAG_NOT_AVAILABLE);
        }

        return found;
    }

    @Override
    public List<Tag> findActive(Collection<Integer> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }

        return mapper.toModel(repository.findByTagIdInAndIsActiveTrueOrderByNameAsc(tagIds));
    }

    private com.exe101.entity.Tag save(com.exe101.entity.Tag entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.TAG_ALREADY_EXISTS);
            }

            throw exception;
        }
    }

    private String normalizeName(String name) {
        String normalized = name == null ? null : name.strip();
        boolean valid = normalized != null && !normalized.isEmpty() && normalized.length() <= maxNameLength;

        if (!valid) {
            throw new AppException(AppErrorMessage.TAG_INVALID);
        }

        return normalized;
    }
}
