package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

class TelegramApiFailureClassifierTest {

  private final TelegramApiFailureClassifier classifier = new TelegramApiFailureClassifier();

  @Test
  void usesStructuredRetryAfterAndFallback() {
    var exact = classifier.classify(request(429, new ResponseParameters(null, 17)));
    assertThat(exact.category()).isEqualTo(TelegramFailureCategory.RATE_LIMITED);
    assertThat(exact.retryAfter()).contains(Duration.ofSeconds(17));

    var fallback = classifier.classify(request(429, null));
    assertThat(fallback.retryAfter()).contains(Duration.ofSeconds(30));
  }

  @ParameterizedTest
  @MethodSource("classifications")
  void classifiesStructuredApiStatus(int code, TelegramFailureCategory expected) {
    assertThat(classifier.classify(request(code, null)).category()).isEqualTo(expected);
  }

  @Test
  void genericApiFailureIsTransient() {
    assertThat(classifier.classify(new TelegramApiException()).category())
        .isEqualTo(TelegramFailureCategory.TRANSIENT);
  }

  private static Stream<Arguments> classifications() {
    return Stream.of(
        Arguments.of(401, TelegramFailureCategory.AUTHENTICATION),
        Arguments.of(400, TelegramFailureCategory.PERMANENT),
        Arguments.of(403, TelegramFailureCategory.PERMANENT),
        Arguments.of(500, TelegramFailureCategory.TRANSIENT),
        Arguments.of(503, TelegramFailureCategory.TRANSIENT));
  }

  private TelegramApiRequestException request(int code, ResponseParameters parameters) {
    var response = new ApiResponse<Void>(false, code, "provider text", parameters, null);
    return new TelegramApiRequestException("sanitized", response);
  }
}
