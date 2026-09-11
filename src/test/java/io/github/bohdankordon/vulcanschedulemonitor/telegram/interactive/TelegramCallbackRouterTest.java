package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringClassSelection;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscription;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.users.ApplicationUser;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

class TelegramCallbackRouterTest {

  private static final long APP_USER = 11;
  private static final long TELEGRAM_USER = 22;
  private static final long PRIVATE_CHAT = 33;

  private final StatefulSubscriptions subscriptions = new StatefulSubscriptions();
  private final RecordingTransport transport = new RecordingTransport();
  private final AtomicInteger registrations = new AtomicInteger();
  private final AtomicLong registeredTelegramUser = new AtomicLong();
  private final AtomicLong registeredChat = new AtomicLong();
  private final TelegramIdentityRegistration identities =
      (telegramUserId, privateChatId) -> {
        registrations.incrementAndGet();
        registeredTelegramUser.set(telegramUserId);
        registeredChat.set(privateChatId);
        return appUser();
      };
  private final ClassSelectionController controller =
      new ClassSelectionController(
          subscriptions,
          ignored -> new VulcanConnectionStatus(VulcanConnectionStatus.State.CONNECTED, 1),
          (chat, text) -> {},
          transport);
  private final TelegramCallbackRouter router =
      new TelegramCallbackRouter(
          new ClassSelectionCallbackParser(), identities, subscriptions, controller, transport);

  @Test
  void privateHumanToggleUsesExactIdentityAnswersAndRefreshesCommittedState() {
    router.route(
        callback("callback-1", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "c1:t:101:0"));

    assertThat(registrations).hasValue(1);
    assertThat(registeredTelegramUser).hasValue(TELEGRAM_USER);
    assertThat(registeredChat).hasValue(PRIVATE_CHAT);
    assertThat(subscriptions.mutations).containsExactly("enable:101");
    assertThat(transport.answers).containsExactly("callback-1:✅ Monitoring enabled.");
    assertThat(transport.edits)
        .singleElement()
        .satisfies(
            message ->
                assertThat(message.keyboard().getFirst().getFirst().text())
                    .isEqualTo("✅ Synthetic class A"));
  }

  @Test
  void localizedClassToggleUsesUserLanguagePreference() {
    var polishPreferences =
        new TelegramLanguagePreferences() {
          @Override
          public io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage getLanguage(
              long appUserId) {
            return io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH;
          }

          @Override
          public void setLanguage(
              long appUserId,
              io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage language) {}
        };
    var polishRouter =
        new TelegramCallbackRouter(
            new ClassSelectionCallbackParser(),
            new LanguageCallbackParser(),
            identities,
            polishPreferences,
            new io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog(),
            subscriptions,
            controller,
            new LanguageSelectionController(
                polishPreferences,
                new io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n
                    .TelegramTextCatalog(),
                transport,
                new io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                    .TelegramCommandMenuService(
                    new io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                        .TelegramCommandMenuTransport() {
                      @Override
                      public void configureDefaultPrivateCommands(
                          java.util.List<
                                  org.telegram.telegrambots.meta.api.objects.commands.BotCommand>
                              commands) {}

                      @Override
                      public void configureChatCommands(
                          long chatId,
                          java.util.List<
                                  org.telegram.telegrambots.meta.api.objects.commands.BotCommand>
                              commands) {}

                      @Override
                      public void configureChatMenuButton(long chatId) {}
                    },
                    new io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n
                        .TelegramTextCatalog())),
            transport);

    polishRouter.route(
        callback("callback-pl", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "c1:t:101:0"));

    assertThat(transport.answers).contains("callback-pl:✅ Monitorowanie włączone.");
  }

  @Test
  void languageCallbackRoutesToLanguageController() {
    var languageSet =
        new java.util.concurrent.atomic.AtomicReference<
            io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage>();
    var preferences =
        new TelegramLanguagePreferences() {
          @Override
          public io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage getLanguage(
              long appUserId) {
            return io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.ENGLISH;
          }

          @Override
          public void setLanguage(
              long appUserId,
              io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage language) {
            languageSet.set(language);
          }
        };
    var lRouter =
        new TelegramCallbackRouter(
            new ClassSelectionCallbackParser(),
            new LanguageCallbackParser(),
            identities,
            preferences,
            new io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog(),
            subscriptions,
            controller,
            new LanguageSelectionController(
                preferences,
                new io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n
                    .TelegramTextCatalog(),
                transport,
                new io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                    .TelegramCommandMenuService(
                    new io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                        .TelegramCommandMenuTransport() {
                      @Override
                      public void configureDefaultPrivateCommands(
                          java.util.List<
                                  org.telegram.telegrambots.meta.api.objects.commands.BotCommand>
                              commands) {}

                      @Override
                      public void configureChatCommands(
                          long chatId,
                          java.util.List<
                                  org.telegram.telegrambots.meta.api.objects.commands.BotCommand>
                              commands) {}

                      @Override
                      public void configureChatMenuButton(long chatId) {}
                    },
                    new io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n
                        .TelegramTextCatalog())),
            transport);

    lRouter.route(
        callback("callback-lang", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "l1:s:pl"));

    assertThat(languageSet.get())
        .isEqualTo(io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH);
    assertThat(transport.answers).contains("callback-lang:✅ Język zmieniono na polski.");
  }

  @Test
  void groupChannelBotAndMissingMessageCallbacksCannotRegisterOrMutate() {
    router.route(callback("group", TELEGRAM_USER, -33, "group", false, "c1:t:101:0"));
    router.route(callback("channel", TELEGRAM_USER, -34, "channel", false, "c1:t:101:0"));
    router.route(callback("bot", TELEGRAM_USER, PRIVATE_CHAT, "private", true, "c1:t:101:0"));
    CallbackQuery missingMessage = new CallbackQuery();
    missingMessage.setId("missing");
    missingMessage.setFrom(new User(TELEGRAM_USER, "Synthetic", false));
    missingMessage.setData("c1:t:101:0");
    router.route(missingMessage);

    assertThat(registrations).hasValue(0);
    assertThat(subscriptions.mutations).isEmpty();
    assertThat(transport.answers).isEmpty();
    assertThat(transport.edits).isEmpty();
  }

  @Test
  void crossUserAndInactiveCatalogCallbacksAreRejectedWithoutMutationAndStillAnswered() {
    router.route(callback("other", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "c1:t:202:0"));
    router.route(callback("stale", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "c1:t:303:0"));

    assertThat(subscriptions.mutations).isEmpty();
    assertThat(transport.answers)
        .containsExactly(
            "other:⚠️ This class is no longer available.",
            "stale:⚠️ This class is no longer available.");
    assertThat(transport.edits).hasSize(2);
  }

  @Test
  void malformedPrivateCallbackIsAnsweredWithoutIdentityRegistrationOrPayloadEcho() {
    router.route(callback("bad", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "c1:t:garbage:0"));

    assertThat(registrations).hasValue(0);
    assertThat(subscriptions.mutations).isEmpty();
    assertThat(transport.answers)
        .containsExactly("bad:⚠️ This class control is no longer valid.")
        .allSatisfy(answer -> assertThat(answer).doesNotContain("garbage"));
  }

  @Test
  void malformedLanguageCallbackIsAnsweredWithoutIdentityRegistrationOrPayloadEcho() {
    router.route(callback("bad-l", TELEGRAM_USER, PRIVATE_CHAT, "private", false, "l1:garbage"));

    assertThat(registrations).hasValue(0);
    assertThat(transport.answers)
        .contains("bad-l:⚠️ This language control is no longer valid.")
        .allSatisfy(answer -> assertThat(answer).doesNotContain("garbage"));
  }

  private static CallbackQuery callback(
      String id, long userId, long chatId, String chatType, boolean bot, String data) {
    Message message =
        Message.builder().messageId(44).chat(new Chat(chatId, chatType)).text("classes").build();
    CallbackQuery query = new CallbackQuery();
    query.setId(id);
    query.setFrom(new User(userId, "Synthetic", bot));
    query.setMessage(message);
    query.setData(data);
    return query;
  }

  private static ApplicationUser appUser() {
    Instant now = Instant.parse("2026-09-04T10:00:00Z");
    return new ApplicationUser(APP_USER, true, now, now);
  }

  private static final class StatefulSubscriptions implements MonitoringSubscriptionService {

    private final Set<Long> subscribed = new HashSet<>();
    private final List<String> mutations = new ArrayList<>();

    @Override
    public MonitoringSubscription enable(long appUserId, long catalogClassId) {
      assertThat(appUserId).isEqualTo(APP_USER);
      if (catalogClassId != 101) {
        throw new IllegalArgumentException("not owned or inactive");
      }
      subscribed.add(catalogClassId);
      mutations.add("enable:" + catalogClassId);
      Instant now = Instant.parse("2026-09-04T10:00:00Z");
      return new MonitoringSubscription(
          1,
          appUserId,
          catalogClassId,
          "Synthetic class A",
          "Synthetic school",
          2026,
          true,
          now,
          now);
    }

    @Override
    public void disable(long appUserId, long catalogClassId) {
      assertThat(appUserId).isEqualTo(APP_USER);
      if (catalogClassId != 101) {
        throw new IllegalArgumentException("not owned");
      }
      subscribed.remove(catalogClassId);
      mutations.add("disable:" + catalogClassId);
    }

    @Override
    public List<MonitoringSubscription> activeSubscriptions(long appUserId) {
      return List.of();
    }

    @Override
    public List<MonitoringClassSelection> availableClasses(long appUserId) {
      return List.of(
          new MonitoringClassSelection(
              101, "Synthetic class A", "Synthetic school", 2026, subscribed.contains(101L)));
    }

    @Override
    public boolean isSubscribed(long appUserId, long catalogClassId) {
      return subscribed.contains(catalogClassId);
    }
  }

  private static final class RecordingTransport implements TelegramInteractiveTransport {

    private final List<TelegramInteractiveMessage> edits = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();

    @Override
    public void send(long privateChatId, TelegramInteractiveMessage message) {}

    @Override
    public void edit(long privateChatId, int messageId, TelegramInteractiveMessage message) {
      edits.add(message);
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
      answers.add(callbackQueryId + ":" + text);
    }
  }
}
