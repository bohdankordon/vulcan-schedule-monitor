package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Explicit test-source composition; never component-scans application services or schedulers. */
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan("io.github.bohdankordon.vulcanschedulemonitor")
@EnableJpaRepositories(basePackageClasses = PersistedBaselineConfiguration.class)
@Import({EncryptedVulcanSecretStore.class, VulcanRecoveryPersistence.class})
public class PersistedBaselineConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  VulcanMasterKey masterKey(Environment environment) {
    return VulcanMasterKey.fromBase64(
        environment.getRequiredProperty("vulcan.connection.master-key"));
  }

  @Bean
  AesGcmCipher cipher(VulcanMasterKey key) {
    return new AesGcmCipher(key);
  }

  @Bean
  SecretPayloadCodec codec() {
    return new SecretPayloadCodec();
  }

  @Bean
  VulcanSessionManager sessions(VulcanSecretStore secrets, VulcanRecoveryPersistence persistence) {
    // The production load path is unchanged. Unused auth/discovery dependencies cannot make
    // traffic.
    return new VulcanSessionManager(
        secrets,
        request -> {
          throw new IllegalStateException("FORBIDDEN_OPERATION");
        },
        material -> {
          throw new IllegalStateException("FORBIDDEN_OPERATION");
        },
        persistence);
  }
}
