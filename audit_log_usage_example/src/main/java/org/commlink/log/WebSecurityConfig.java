package org.commlink.log;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableAsync
public class WebSecurityConfig {

    /**
     * Demo-only: permits every request and disables CSRF so the sample endpoints are reachable
     * without setting up authentication. Do not carry this into a real application - lock down
     * {@code authorizeHttpRequests} and re-enable CSRF for any state-changing endpoint.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(req ->
                        req.requestMatchers("/**")
                                .permitAll());

        return http.build();
    }
}
