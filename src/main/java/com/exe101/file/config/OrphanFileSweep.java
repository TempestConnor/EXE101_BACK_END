package com.exe101.file.config;

import com.exe101.file.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Bytes are written before their StoredFile row commits, so a rollback leaves them on disk without a row.
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanFileSweep {
    private final FileService fileService;

    @Scheduled(
        initialDelayString = "${app.storage.orphan-sweep-interval}",
        fixedDelayString = "${app.storage.orphan-sweep-interval}")
    public void sweep() {
        int deleted = fileService.deleteOrphans();
        log.info("[FILE] - ACTION: orphanSweep: deleted: {}", deleted);
    }
}
