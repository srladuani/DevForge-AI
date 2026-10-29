package ai.devforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class DevForgeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(DevForgeBackendApplication.class, args);
	}

}
