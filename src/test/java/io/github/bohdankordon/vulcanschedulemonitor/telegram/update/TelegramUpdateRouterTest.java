package io.github.bohdankordon.vulcanschedulemonitor.telegram.update;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscription;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.ConnectCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.HelpCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.StartCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.StatusCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.SubscriptionsCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommandParser;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.users.ApplicationUser;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

class TelegramUpdateRouterTest {

  @Test
  void supportedPrivateCommandsRegisterExactIdentityAndReplyThroughHandlers() {
    var telegramUser = new AtomicLong();
    var privateChat = new AtomicLong();
    TelegramIdentityRegistration identities =
        (userId, chatId) -> {
          telegramUser.set(userId);
          privateChat.set(chatId);
          return appUser();
        };
    var replies = new ArrayList<String>();
    var router = router(identities, (chat, text) -> replies.add(text));

    for (String command : List.of("/start", "/help", "/status", "/subscriptions", "/connect")) {
      router.route(update(1, 4001, 5001, "private", false, command));
    }

    assertThat(telegramUser).hasValue(4001);
    assertThat(privateChat).hasValue(5001);
    assertThat(replies)
        .hasSize(5)
        .anySatisfy(text -> assertThat(text).contains("Never send VULCAN credentials"))
        .anySatisfy(text -> assertThat(text).contains("Supported commands"))
        .anySatisfy(text -> assertThat(text).contains("Active monitoring subscriptions: 2"))
        .anySatisfy(text -> assertThat(text).contains("Schedule references: #42, #51"))
        .anySatisfy(text -> assertThat(text).contains("HTTPS web page"));
  }

  @Test
  void unsupportedUpdateShapesAreIgnoredWithoutRegistrationOrReply() {
    var registrations = new AtomicInteger();
    TelegramIdentityRegistration identities =
        (user, chat) -> {
          registrations.incrementAndGet();
          return appUser();
        };
    var replies = new ArrayList<String>();
    var router = router(identities, (chat, text) -> replies.add(text));

    router.route(update(1, 4001, -5001, "group", false, "/start"));
    router.route(update(2, 4001, -5001, "supergroup", false, "/start"));
    router.route(update(3, 4001, -5001, "channel", false, "/start"));
    router.route(update(4, 4001, 5001, "private", true, "/start"));
    router.route(updateWithoutSender());
    router.route(updateWithoutChat());
    router.route(update(7, 4001, 5001, "private", false, null));
    router.route(new Update());
    var edited = new Update();
    edited.setEditedMessage(message(4001, 5001, "private", false, "/start"));
    router.route(edited);
    router.route(update(10, 4001, 5001, "private", false, "plain text"));
    router.route(update(11, 4001, 5001, "private", false, "/subscribe 42"));

    assertThat(registrations).hasValue(0);
    assertThat(replies).isEmpty();
  }

  @Test
  void consumerIsolatesOneFailingUpdateWithinBatch() {
    TelegramIdentityRegistration identities =
        (user, chat) -> {
          if (user == 4999) {
            throw new IllegalStateException("synthetic failure");
          }
          return appUser();
        };
    var replies = new ArrayList<String>();
    var consumer =
        new TelegramUpdateConsumer(router(identities, (chat, text) -> replies.add(text)));

    consumer.consume(
        List.of(
            update(1, 4001, 5001, "private", false, "/help"),
            update(2, 4999, 5999, "private", false, "/help"),
            update(3, 4002, 5002, "private", false, "/help")));

    assertThat(replies).hasSize(2);
  }

  private TelegramUpdateRouter router(
      TelegramIdentityRegistration identities, TelegramMessageTransport transport) {
    MonitoringSubscriptionService subscriptions =
        new MonitoringSubscriptionService() {
          @Override
          public MonitoringSubscription enable(long appUserId, long journalId) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void disable(long appUserId, long journalId) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<Long> activeJournalIds(long appUserId) {
            assertThat(appUserId).isEqualTo(1001);
            return List.of(42L, 51L);
          }

          @Override
          public boolean isSubscribed(long appUserId, long journalId) {
            return false;
          }
        };
    return new TelegramUpdateRouter(
        new TelegramCommandParser(),
        identities,
        transport,
        List.of(
            new StartCommandHandler(),
            new HelpCommandHandler(),
            new StatusCommandHandler(subscriptions),
            new SubscriptionsCommandHandler(subscriptions),
            new ConnectCommandHandler()));
  }

  private Update update(
      int updateId, long userId, long chatId, String chatType, boolean bot, String text) {
    var update = new Update();
    update.setUpdateId(updateId);
    update.setMessage(message(userId, chatId, chatType, bot, text));
    return update;
  }

  private Update updateWithoutSender() {
    var update = new Update();
    update.setMessage(Message.builder().chat(new Chat(5001L, "private")).text("/start").build());
    return update;
  }

  private Update updateWithoutChat() {
    var update = new Update();
    update.setMessage(
        Message.builder().from(new User(4001L, "Synthetic", false)).text("/start").build());
    return update;
  }

  private Message message(long userId, long chatId, String chatType, boolean bot, String text) {
    return Message.builder()
        .from(new User(userId, "Synthetic", bot))
        .chat(new Chat(chatId, chatType))
        .text(text)
        .build();
  }

  private ApplicationUser appUser() {
    Instant now = Instant.parse("2026-09-04T10:00:00Z");
    return new ApplicationUser(1001, true, now, now);
  }
}
