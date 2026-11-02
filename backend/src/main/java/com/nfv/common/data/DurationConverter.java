package com.nfv.common.data;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Duration;

@Converter(autoApply = true)
public class DurationConverter implements AttributeConverter<Duration, Long> {

    @Override
    public Long convertToDatabaseColumn(Duration duration) {
        return (duration == null) ? null : duration.toNanos();
    }

    @Override
    public Duration convertToEntityAttribute(Long nanos) {
        return (nanos == null) ? null : Duration.ofNanos(nanos);
    }
}
