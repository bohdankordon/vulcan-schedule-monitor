package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringClassSelection;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassSelectionControllerTest {

  private static final long USER = 11;
  private static final long CHAT = 22;

  private final MonitoringSubscriptionService subscriptions =
      mock(MonitoringSubscriptionService.class);
  private final VulcanConnectionStatusService connections =
      mock(VulcanConnectionStatusService.class);
  private final RecordingTransport interactive = new RecordingTransport();
  private final List<String> plain = new ArrayList<>();
  private final TelegramMessageTransport plainTransport = (chat, text) -> plain.add(text);
  private final ClassSelectionController controller =
      new ClassSelectionController(subscriptions, connections, plainTransport, interactive);

  @Test
  void guidesUsersThroughMissingAndReconnectRequiredStatesWithoutAKeyboard() throws Exception {
    when(connections.statusForUser(USER))
        .thenReturn(
            new VulcanConnectionStatus(VulcanConnectionStatus.State.NOT_CONNECTED, 0),
            new VulcanConnectionStatus(VulcanConnectionStatus.State.RECONNECT_REQUIRED, 0));

    controller.send(USER, CHAT, 0);
    controller.send(USER, CHAT, 0);

    assertThat(plain).hasSize(2).first().asString().contains("/connect", "No VULCAN account");
    assertThat(plain.get(1)).contains("/connect", "reconnect");
    assertThat(interactive.sent).isEmpty();
  }

  @Test
  void connectedAccountWithNoClassesGetsAnExplicitEmptyState() throws Exception {
    connected();
    when(subscriptions.availableClasses(USER)).thenReturn(List.of());

    controller.send(USER, CHAT, 0);

    assertThat(plain).singleElement().asString().contains("No available classes");
  }

  @Test
  void rendersOneAvailableClassWithoutPaginationOrInternalIds() throws Exception {
    connected();
    when(subscriptions.availableClasses(USER))
        .thenReturn(
            List.of(
                new MonitoringClassSelection(
                    9_001, "Synthetic class A", "Synthetic school", 2026, false)));

    controller.send(USER, CHAT, 0);

    assertThat(interactive.sent)
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.text()).isEqualTo("Choose classes to monitor (page 1 of 1).");
              assertThat(message.keyboard())
                  .containsExactly(
                      List.of(new TelegramInlineButton("☐ Synthetic class A", "c1:t:9001:0")));
              assertThat(message.text()).doesNotContain("9001", "journal", "catalog");
              assertThat(message.keyboard().getFirst().getFirst().text())
                  .doesNotContain("9001", "journal", "catalog");
            });
  }

  @Test
  void rendersDeterministicSelectionMarkersAndPaginationWithoutVisibleInternalIds()
      throws Exception {
    connected();
    List<MonitoringClassSelection> selections = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      selections.add(
          new MonitoringClassSelection(
              9000L + index,
              "Synthetic class " + (char) ('A' + index),
              "Synthetic school",
              2026,
              index == 1));
    }
    when(subscriptions.availableClasses(USER)).thenReturn(List.copyOf(selections));

    controller.send(USER, CHAT, 0);
    controller.edit(USER, CHAT, 33, 1);

    TelegramInteractiveMessage first = interactive.sent.getFirst();
    assertThat(first.text()).isEqualTo("Choose classes to monitor (page 1 of 2).");
    assertThat(first.keyboard()).hasSize(9);
    assertThat(first.keyboard().get(0).getFirst().text()).isEqualTo("☐ Synthetic class A");
    assertThat(first.keyboard().get(1).getFirst().text()).isEqualTo("✅ Synthetic class B");
    assertThat(first.keyboard().get(8).getFirst())
        .isEqualTo(new TelegramInlineButton("Next", "c1:p:1"));
    assertThat(first.text()).doesNotContain("9000", "77", "journal", "catalog");
    assertThat(first.keyboard())
        .flatExtracting(row -> row)
        .extracting(TelegramInlineButton::text)
        .allSatisfy(text -> assertThat(text).doesNotContain("9000", "journal", "catalog"));

    TelegramInteractiveMessage second = interactive.edited.getFirst();
    assertThat(second.text()).isEqualTo("Choose classes to monitor (page 2 of 2).");
    assertThat(second.keyboard()).hasSize(3);
    assertThat(second.keyboard().get(0).getFirst().text()).contains("Synthetic class I");
    assertThat(second.keyboard().get(2).getFirst())
        .isEqualTo(new TelegramInlineButton("Previous", "c1:p:0"));
  }

  private void connected() {
    when(connections.statusForUser(USER))
        .thenReturn(new VulcanConnectionStatus(VulcanConnectionStatus.State.CONNECTED, 10));
  }

  private static final class RecordingTransport implements TelegramInteractiveTransport {

    private final List<TelegramInteractiveMessage> sent = new ArrayList<>();
    private final List<TelegramInteractiveMessage> edited = new ArrayList<>();

    @Override
    public void send(long privateChatId, TelegramInteractiveMessage message) {
      sent.add(message);
    }

    @Override
    public void edit(long privateChatId, int messageId, TelegramInteractiveMessage message) {
      edited.add(message);
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {}
  }
}
