package com.flowops.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI / Swagger UI metadata. Swagger UI is served at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flowOpsOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Vantis API")
                .description("Vantis is reliability infrastructure for automation — helping teams build, monitor, detect abnormal behavior, and investigate workflow failures.")
                .version("0.1.0")
                .contact(new Contact().name("Vantis").url("https://vantis.run"))
                .license(new License().name("Proprietary")));
    }
}
