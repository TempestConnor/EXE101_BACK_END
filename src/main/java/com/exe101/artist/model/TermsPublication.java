package com.exe101.artist.model;

import java.util.List;
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
public class TermsPublication {
    private String text;
    private List<Long> keepImageIds;
    private List<TermsImageUpload> uploads;
}
