package com.exe101.commission.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class DeliverableFile {
    private Long fileId;
    private String originalName;
    private String contentType;
    private long byteLength;
}
