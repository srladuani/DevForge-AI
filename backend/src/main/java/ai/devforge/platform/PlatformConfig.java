package ai.devforge.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
