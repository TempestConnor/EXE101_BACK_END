package com.exe101.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.core.io.Resource;

// The bytes are streamed from storage when the response is written, not held in memory.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class FileContent {
    private Long fileId;
    private String originalName;
    private String contentType;
    private long byteLength;
    private Resource content;
}
