package com.exe101.tag.service;

import com.exe101.tag.model.NewTag;
import com.exe101.tag.model.Tag;
import com.exe101.tag.model.TagChange;
import java.util.Collection;
import java.util.List;

public interface TagService {
    Tag create(NewTag tag);

    Tag update(Integer tagId, TagChange change);

    List<Tag> listAll();

    List<Tag> listActive();

    List<Tag> requireActive(Collection<Integer> tagIds);

    List<Tag> findActive(Collection<Integer> tagIds);
}
