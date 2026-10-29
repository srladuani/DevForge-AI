package ai.devforge.platform;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Makes the published API contract honest: every field of every record is REQUIRED unless it is annotated {@code @Nullable}. Without this
 * springdoc marks everything optional, which forces clients to guard against absent fields that are in fact always present.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI devForgeApi() {
        return new OpenAPI().info(new Info().title("DevForge AI API").version("1.0.0-m1")
            .description("Projects, repositories, runs, approvals and per-user connections. Errors are RFC 7807 problem+json with a stable `code`."));
    }

    @Bean
    OpenApiCustomizer requiredUnlessNullable() {
        Map<String, Class<?>> records = scanRecords();
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;
            openApi.getComponents().getSchemas().forEach((name, schema) -> {
                Class<?> type = records.get(name);
                if (type == null || schema.getProperties() == null) return;
                List<String> required = new ArrayList<>();
                for (RecordComponent rc : type.getRecordComponents()) {
                    boolean nullable = rc.getAnnotatedType().isAnnotationPresent(Nullable.class) || rc.isAnnotationPresent(Nullable.class);
                    if (!nullable && schema.getProperties().containsKey(rc.getName())) required.add(rc.getName());
                }
                schema.setRequired(required.isEmpty() ? null : required);
            });
        };
    }

    private static Map<String, Class<?>> scanRecords() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        Map<String, Class<?>> out = new HashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("ai.devforge")) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                out.putIfAbsent(c.getSimpleName(), c);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }
}
