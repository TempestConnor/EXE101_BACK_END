package com.exe101.tag.repository;

import com.exe101.entity.Tag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Integer> {
    boolean existsByName(String name);

    boolean existsByNameAndTagIdNot(String name, Integer tagId);

    List<Tag> findAllByOrderByNameAsc();

    List<Tag> findByIsActiveTrueOrderByNameAsc();

    List<Tag> findByTagIdInAndIsActiveTrueOrderByNameAsc(Collection<Integer> tagIds);
}
