package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class VulcanConnectSecurityConfiguration {

  @Bean
  SecurityFilterChain vulcanConnectSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers("/connect", "/connect/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .headers(
            headers ->
                headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(contentType -> {})
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'none'; style-src 'unsafe-inline'; "
                                    + "form-action 'self'; frame-ancestors 'none'; base-uri 'none'")));
    return http.build();
  }

  @Bean
  OncePerRequestFilter vulcanConnectNoStoreFilter() {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
          throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/connect")) {
          response.setHeader("Cache-Control", "no-store");
          response.setHeader("Pragma", "no-cache");
        }
        filterChain.doFilter(request, response);
      }
    };
  }
}
