package com.exe101.proceeds.model;

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
public class ReleaseOutcome {
    private ReleaseStatus status;
    private String transferReference;
    private String reason;
}
