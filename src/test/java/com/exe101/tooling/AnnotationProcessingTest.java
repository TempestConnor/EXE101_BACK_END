package com.exe101.tooling;

import lombok.Builder;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationProcessingTest {
    @Test
    void generatedMapper_whenUsingLombokBuilder_shouldRoundTripAsSpringBean() {
        // Given
        try (var context = new AnnotationConfigApplicationContext()) {
            context.scan("com.exe101.tooling");
            context.refresh();
            var mapper = context.getBean(SampleMapper.class);
            var source = Sample.builder().withName("processor-check").build();

            // When
            var dto = mapper.toDto(source);
            var restored = mapper.toModel(dto);

            // Then
            assertThat(dto.displayName()).isEqualTo("processor-check");
            assertThat(restored.getName()).isEqualTo("processor-check");
        }
    }

    @Getter
    @Builder(setterPrefix = "with")
    public static class Sample {
        private String name;
    }

    public record SampleDto(String displayName) { }

    @Mapper(componentModel = "spring")
    public interface SampleMapper {
        @Mapping(source = "name", target = "displayName")
        SampleDto toDto(Sample source);

        @Mapping(source = "displayName", target = "withName")
        Sample toModel(SampleDto source);
    }
}
