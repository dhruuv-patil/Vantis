package com.flowops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Isolated configuration for the {@link PasswordEncoder} bean.
 *
 * <p>Separating the encoder from {@link SecurityConfig} avoids circular dependencies
 * between the security filter chain, authentication handlers, and {@link com.flowops.auth.AuthService}.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }
}
