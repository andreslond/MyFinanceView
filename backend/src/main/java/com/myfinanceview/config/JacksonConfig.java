package com.myfinanceview.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Jackson wiring for the MVP REST API — design.md D6 + D7 + spec.md requirement
 * <i>"BigDecimal serialization preserves precision"</i>.
 *
 * <p>Two contributions:
 * <ol>
 *   <li>{@link JavaTimeModule} registered + {@code WRITE_DATES_AS_TIMESTAMPS} disabled, so
 *       {@link java.time.OffsetDateTime} serialises as ISO-8601 with the {@code Z} offset
 *       (e.g. {@code "2026-06-01T15:30:00Z"}) instead of epoch milliseconds.</li>
 *   <li>Custom {@link JsonSerializer} for {@link BigDecimal} that writes
 *       {@code value.toPlainString()} <b>as a string</b> (not a JSON number). This guarantees:
 *       <ul>
 *         <li>{@code new BigDecimal("0.10")} → {@code "0.10"} (scale-2 preserved, not {@code "0.1"}).</li>
 *         <li>JS clients receive a string they can hand to {@code Intl.NumberFormat} without
 *             round-tripping through {@code Number(...)} (which would lose precision on values
 *             larger than {@code Number.MAX_SAFE_INTEGER}).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Contributing via {@link Jackson2ObjectMapperBuilderCustomizer} (instead of replacing the
 * {@code ObjectMapper} bean) keeps every Spring Boot module that uses Jackson (web MVC, the
 * actuator, the OAuth2 resource server's ProblemDetail writer) in sync with this configuration.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonBuilderCustomizer() {
        return builder -> {
            builder.modules(new JavaTimeModule(), bigDecimalAsStringModule());
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }

    /**
     * Module exposing a single serializer for {@link BigDecimal}. Deserialization continues to use
     * Jackson's default {@code BigDecimalDeserializer}, which already accepts both quoted strings
     * and numeric JSON tokens — i.e. inbound is permissive, outbound is normalised.
     */
    static SimpleModule bigDecimalAsStringModule() {
        SimpleModule module = new SimpleModule("MyFinanceViewBigDecimal");
        module.addSerializer(BigDecimal.class, new BigDecimalAsStringSerializer());
        return module;
    }

    /**
     * Writes a {@link BigDecimal} as a quoted JSON string via {@link BigDecimal#toPlainString()},
     * which preserves the value's scale (trailing zeros are kept). Null values defer to the
     * default null handling configured upstream.
     */
    static final class BigDecimalAsStringSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
            // writeString() forces the value into a quoted string regardless of any
            // WRITE_BIGDECIMAL_AS_PLAIN feature flag — that flag controls the numeric form only.
            gen.writeString(value.toPlainString());
        }
    }
}
