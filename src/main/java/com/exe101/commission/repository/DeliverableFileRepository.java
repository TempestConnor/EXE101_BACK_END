package com.exe101.commission.repository;

import com.exe101.entity.DeliverableFile;
import com.exe101.entity.DeliverableFileId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliverableFileRepository extends JpaRepository<DeliverableFile, DeliverableFileId> {
    List<DeliverableFile> findByIdDeliverableIdIn(Collection<Long> deliverableIds);
}
