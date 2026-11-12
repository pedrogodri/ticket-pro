package br.furb.ingressoja.auth.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Validates environment configuration at boot; an invalid variable aborts startup. */
@Configuration(proxyBeanMethods = false)
public class AppConfigConfiguration {

  @Bean
  public AppConfig appConfig() {
    return AppConfig.from(System.getenv());
  }
}
