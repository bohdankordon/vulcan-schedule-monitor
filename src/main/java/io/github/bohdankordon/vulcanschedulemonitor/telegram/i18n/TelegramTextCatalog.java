package io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationEventType;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommand;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TelegramTextCatalog {

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  public String initialLanguagePrompt() {
    return "🌐 Choose your language\nВыберите язык · Оберіть мову · Wybierz język";
  }

  public String languageDisplayName(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "🇬🇧 English";
      case RUSSIAN -> "🇷🇺 Русский";
      case UKRAINIAN -> "🇺🇦 Українська";
      case POLISH -> "🇵🇱 Polski";
    };
  }

  public String languageChanged(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "✅ Language changed to English.";
      case RUSSIAN -> "✅ Язык изменён на русский.";
      case UKRAINIAN -> "✅ Мову змінено на українську.";
      case POLISH -> "✅ Język zmieniono na polski.";
    };
  }

  public String welcome(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH ->
          """
          👋 Welcome to Vulcan Schedule Monitor!

          I'll monitor the schedules of your selected classes and notify you when something changes in VULCAN.

          🔐 /connect — securely connect VULCAN
          🎓 /classes — choose classes to monitor
          🔔 /subscriptions — view monitored classes
          📊 /status — check status
          🌐 /language — change language
          ❓ /help — show all commands

          🔒 Enter your VULCAN login and password only on the secure HTTPS page. Never send them in Telegram.""";
      case RUSSIAN ->
          """
          👋 Добро пожаловать в Vulcan Schedule Monitor!

          Я буду следить за расписанием выбранных классов и сообщать, когда в VULCAN появятся изменения.

          🔐 /connect — безопасно подключить VULCAN
          🎓 /classes — выбрать классы для отслеживания
          🔔 /subscriptions — посмотреть отслеживаемые классы
          📊 /status — проверить состояние
          🌐 /language — изменить язык
          ❓ /help — показать все команды

          🔒 Логин и пароль VULCAN вводите только на защищённой HTTPS-странице. Никогда не отправляйте их в Telegram.""";
      case UKRAINIAN ->
          """
          👋 Ласкаво просимо до Vulcan Schedule Monitor!

          Я стежитиму за розкладом вибраних класів і повідомлятиму, коли у VULCAN з'являться зміни.

          🔐 /connect — безпечно підключити VULCAN
          🎓 /classes — обрати класи для відстеження
          🔔 /subscriptions — переглянути відстежувані класи
          📊 /status — перевірити стан
          🌐 /language — змінити мову
          ❓ /help — показати всі команди

          🔒 Логін і пароль VULCAN вводьте лише на захищеній HTTPS-сторінці. Ніколи не надсилайте їх у Telegram.""";
      case POLISH ->
          """
          👋 Witaj w Vulcan Schedule Monitor!

          Będę monitorować plany wybranych klas i powiadomię Cię, gdy w VULCAN pojawią się zmiany.

          🔐 /connect — bezpiecznie połącz VULCAN
          🎓 /classes — wybierz klasy do monitorowania
          🔔 /subscriptions — zobacz monitorowane klasy
          📊 /status — sprawdź stan
          🌐 /language — zmień język
          ❓ /help — pokaż wszystkie polecenia

          🔒 Login i hasło do VULCAN wpisuj wyłącznie na zabezpieczonej stronie HTTPS. Nigdy nie wysyłaj ich przez Telegram.""";
    };
  }

  public String help(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH ->
          """
          ❓ Commands

          🔐 /connect — securely connect VULCAN
          🎓 /classes — choose classes to monitor
          🔔 /subscriptions — view monitored classes
          📊 /status — check status
          🌐 /language — change language
          ▶️ /start — start again / choose language
          ❓ /help — show this help

          🔒 Never send your VULCAN login or password in Telegram.""";
      case RUSSIAN ->
          """
          ❓ Команды

          🔐 /connect — безопасно подключить VULCAN
          🎓 /classes — выбрать классы для отслеживания
          🔔 /subscriptions — показать отслеживаемые классы
          📊 /status — проверить состояние
          🌐 /language — изменить язык
          ▶️ /start — начать заново / выбрать язык
          ❓ /help — показать эту справку

          🔒 Никогда не отправляйте логин или пароль VULCAN в Telegram.""";
      case UKRAINIAN ->
          """
          ❓ Команди

          🔐 /connect — безпечно підключити VULCAN
          🎓 /classes — обрати класи для відстеження
          🔔 /subscriptions — показати відстежувані класи
          📊 /status — перевірити стан
          🌐 /language — змінити мову
          ▶️ /start — почати знову / обрати мову
          ❓ /help — показати цю довідку

          🔒 Ніколи не надсилайте логін або пароль VULCAN у Telegram.""";
      case POLISH ->
          """
          ❓ Polecenia

          🔐 /connect — bezpiecznie połącz VULCAN
          🎓 /classes — wybierz klasy do monitorowania
          🔔 /subscriptions — pokaż monitorowane klasy
          📊 /status — sprawdź stan
          🌐 /language — zmień język
          ▶️ /start — zacznij ponownie / wybierz język
          ❓ /help — pokaż tę pomoc

          🔒 Nigdy nie wysyłaj loginu ani hasła VULCAN przez Telegram.""";
    };
  }

  public String status(
      TelegramLanguage language,
      VulcanConnectionStatus.State state,
      int availableClasses,
      int monitoredClasses) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(state, "state must not be null");
    return switch (language) {
      case ENGLISH ->
          switch (state) {
            case CONNECTED ->
                "📊 Status\n\n🟢 VULCAN: Connected\n🎓 Available classes: "
                    + availableClasses
                    + "\n🔔 Monitored classes: "
                    + monitoredClasses;
            case RECONNECT_REQUIRED ->
                "📊 Status\n\n🟠 VULCAN: Reconnect required\nUse /connect to reconnect.\n🎓 Available classes: "
                    + availableClasses
                    + "\n🔔 Monitored classes: "
                    + monitoredClasses;
            case NOT_CONNECTED ->
                "📊 Status\n\n⚪ VULCAN: Not connected\nUse /connect to get started.\n🎓 Available classes: "
                    + availableClasses
                    + "\n🔔 Monitored classes: "
                    + monitoredClasses;
          };
      case RUSSIAN ->
          switch (state) {
            case CONNECTED ->
                "📊 Состояние\n\n🟢 VULCAN: подключён\n🎓 Доступных классов: "
                    + availableClasses
                    + "\n🔔 Отслеживаемых классов: "
                    + monitoredClasses;
            case RECONNECT_REQUIRED ->
                "📊 Состояние\n\n🟠 VULCAN: требуется переподключение\nИспользуйте /connect, чтобы подключиться заново.\n🎓 Доступных классов: "
                    + availableClasses
                    + "\n🔔 Отслеживаемых классов: "
                    + monitoredClasses;
            case NOT_CONNECTED ->
                "📊 Состояние\n\n⚪ VULCAN: не подключён\nИспользуйте /connect, чтобы начать.\n🎓 Доступных классов: "
                    + availableClasses
                    + "\n🔔 Отслеживаемых классов: "
                    + monitoredClasses;
          };
      case UKRAINIAN ->
          switch (state) {
            case CONNECTED ->
                "📊 Стан\n\n🟢 VULCAN: підключено\n🎓 Доступних класів: "
                    + availableClasses
                    + "\n🔔 Відстежуваних класів: "
                    + monitoredClasses;
            case RECONNECT_REQUIRED ->
                "📊 Стан\n\n🟠 VULCAN: потрібне повторне підключення\nСкористайтеся /connect, щоб підключитися знову.\n🎓 Доступних класів: "
                    + availableClasses
                    + "\n🔔 Відстежуваних класів: "
                    + monitoredClasses;
            case NOT_CONNECTED ->
                "📊 Стан\n\n⚪ VULCAN: не підключено\nСкористайтеся /connect, щоб почати.\n🎓 Доступних класів: "
                    + availableClasses
                    + "\n🔔 Відстежуваних класів: "
                    + monitoredClasses;
          };
      case POLISH ->
          switch (state) {
            case CONNECTED ->
                "📊 Stan\n\n🟢 VULCAN: połączony\n🎓 Dostępne klasy: "
                    + availableClasses
                    + "\n🔔 Monitorowane klasy: "
                    + monitoredClasses;
            case RECONNECT_REQUIRED ->
                "📊 Stan\n\n🟠 VULCAN: wymagane ponowne połączenie\nUżyj /connect, aby połączyć konto ponownie.\n🎓 Dostępne klasy: "
                    + availableClasses
                    + "\n🔔 Monitorowane klasy: "
                    + monitoredClasses;
            case NOT_CONNECTED ->
                "📊 Stan\n\n⚪ VULCAN: niepołączony\nUżyj /connect, aby rozpocząć.\n🎓 Dostępne klasy: "
                    + availableClasses
                    + "\n🔔 Monitorowane klasy: "
                    + monitoredClasses;
          };
    };
  }

  public String subscriptionsEmpty(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "🔕 No classes are being monitored yet.\nUse /classes to choose a class.";
      case RUSSIAN ->
          "🔕 Пока ни один класс не отслеживается.\nИспользуйте /classes, чтобы выбрать класс.";
      case UKRAINIAN ->
          "🔕 Поки жоден клас не відстежується.\nСкористайтеся /classes, щоб обрати клас.";
      case POLISH ->
          "🔕 Żadna klasa nie jest jeszcze monitorowana.\nUżyj /classes, aby wybrać klasę.";
    };
  }

  public String subscriptions(TelegramLanguage language, List<String> classNames) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(classNames, "classNames must not be null");
    if (classNames.isEmpty()) {
      return subscriptionsEmpty(language);
    }
    String header =
        switch (language) {
          case ENGLISH -> "🔔 Monitored classes";
          case RUSSIAN -> "🔔 Отслеживаемые классы";
          case UKRAINIAN -> "🔔 Відстежувані класи";
          case POLISH -> "🔔 Monitorowane klasy";
        };
    StringBuilder builder = new StringBuilder(header).append("\n\n");
    for (int index = 0; index < classNames.size(); index++) {
      builder.append("• ").append(classNames.get(index));
      if (index < classNames.size() - 1) {
        builder.append("\n");
      }
    }
    return builder.toString();
  }

  public String connectLink(TelegramLanguage language, String url) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(url, "url must not be null");
    return switch (language) {
      case ENGLISH ->
          """
          🔐 Secure VULCAN connection

          Open the link below to connect your account.
          The link is short-lived and can be used only once:

          %s

          🔒 Enter your VULCAN login and password only on the HTTPS page.
          Never send them in Telegram."""
              .formatted(url);
      case RUSSIAN ->
          """
          🔐 Безопасное подключение VULCAN

          Откройте ссылку ниже, чтобы подключить аккаунт.
          Ссылка действует ограниченное время и может быть использована только один раз:

          %s

          🔒 Вводите логин и пароль VULCAN только на HTTPS-странице.
          Никогда не отправляйте их в Telegram."""
              .formatted(url);
      case UKRAINIAN ->
          """
          🔐 Безпечне підключення VULCAN

          Відкрийте посилання нижче, щоб підключити обліковий запис.
          Посилання діє обмежений час і може бути використане лише один раз:

          %s

          🔒 Вводьте логін і пароль VULCAN лише на HTTPS-сторінці.
          Ніколи не надсилайте їх у Telegram."""
              .formatted(url);
      case POLISH ->
          """
          🔐 Bezpieczne połączenie z VULCAN

          Otwórz poniższy link, aby połączyć konto.
          Link jest ważny przez krótki czas i można go użyć tylko raz:

          %s

          🔒 Login i hasło VULCAN wpisuj wyłącznie na stronie HTTPS.
          Nigdy nie wysyłaj ich przez Telegram."""
              .formatted(url);
    };
  }

  public String connectDisabled(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH ->
          """
          🔒 Secure VULCAN connection is currently disabled by the operator.
          Credentials belong only on the secure HTTPS page when enabled. Never send them in Telegram.""";
      case RUSSIAN ->
          """
          🔒 Безопасное подключение VULCAN сейчас отключено оператором.
          Логин и пароль вводятся только на защищённой HTTPS-странице, когда подключение включено. Никогда не отправляйте их в Telegram.""";
      case UKRAINIAN ->
          """
          🔒 Безпечне підключення VULCAN зараз вимкнено оператором.
          Логін і пароль вводяться лише на захищеній HTTPS-сторінці, коли підключення ввімкнено. Ніколи не надсилайте їх у Telegram.""";
      case POLISH ->
          """
          🔒 Bezpieczne połączenie z VULCAN jest obecnie wyłączone przez operatora.
          Login i hasło wpisuje się wyłącznie na zabezpieczonej stronie HTTPS, gdy funkcja jest aktywna. Nigdy nie wysyłaj ich przez Telegram.""";
    };
  }

  public String classSelectionHeader(TelegramLanguage language, int page, int pageCount) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH ->
          "🎓 Classes to monitor\nTap a class to turn notifications on or off.\nPage "
              + (page + 1)
              + "/"
              + pageCount;
      case RUSSIAN ->
          "🎓 Классы для отслеживания\nНажмите на класс, чтобы включить или выключить уведомления.\nСтраница "
              + (page + 1)
              + "/"
              + pageCount;
      case UKRAINIAN ->
          "🎓 Класи для відстеження\nНатисніть на клас, щоб увімкнути або вимкнути сповіщення.\nСторінка "
              + (page + 1)
              + "/"
              + pageCount;
      case POLISH ->
          "🎓 Klasy do monitorowania\nWybierz klasę, aby włączyć lub wyłączyć powiadomienia.\nStrona "
              + (page + 1)
              + "/"
              + pageCount;
    };
  }

  public String previous(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "⬅️ Previous";
      case RUSSIAN, UKRAINIAN -> "⬅️ Назад";
      case POLISH -> "⬅️ Wstecz";
    };
  }

  public String next(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "Next ➡️";
      case RUSSIAN -> "Далее ➡️";
      case UKRAINIAN -> "Далі ➡️";
      case POLISH -> "Dalej ➡️";
    };
  }

  public String classSelectionNotConnected(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "⚪ VULCAN is not connected.\nUse /connect first.";
      case RUSSIAN -> "⚪ VULCAN не подключён.\nСначала используйте /connect.";
      case UKRAINIAN -> "⚪ VULCAN не підключено.\nСпочатку скористайтеся /connect.";
      case POLISH -> "⚪ VULCAN nie jest połączony.\nNajpierw użyj /connect.";
    };
  }

  public String classSelectionReconnectRequired(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "🟠 Your VULCAN connection needs to be renewed.\nUse /connect to reconnect.";
      case RUSSIAN ->
          "🟠 Подключение VULCAN нужно обновить.\nИспользуйте /connect для повторного подключения.";
      case UKRAINIAN ->
          "🟠 Підключення VULCAN потрібно оновити.\nСкористайтеся /connect для повторного підключення.";
      case POLISH ->
          "🟠 Połączenie z VULCAN wymaga odnowienia.\nUżyj /connect, aby połączyć konto ponownie.";
    };
  }

  public String classSelectionEmpty(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "🎓 No classes were found for this VULCAN account.";
      case RUSSIAN -> "🎓 Для этого аккаунта VULCAN классы не найдены.";
      case UKRAINIAN -> "🎓 Для цього облікового запису VULCAN класи не знайдено.";
      case POLISH -> "🎓 Nie znaleziono klas dla tego konta VULCAN.";
    };
  }

  public String classListRefreshed(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "🔄 Class list refreshed.";
      case RUSSIAN -> "🔄 Список классов обновлён.";
      case UKRAINIAN -> "🔄 Список класів оновлено.";
      case POLISH -> "🔄 Lista klas została odświeżona.";
    };
  }

  public String monitoringEnabled(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "✅ Monitoring enabled.";
      case RUSSIAN -> "✅ Отслеживание включено.";
      case UKRAINIAN -> "✅ Відстеження увімкнено.";
      case POLISH -> "✅ Monitorowanie włączone.";
    };
  }

  public String monitoringDisabled(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "🔕 Monitoring disabled.";
      case RUSSIAN -> "🔕 Отслеживание выключено.";
      case UKRAINIAN -> "🔕 Відстеження вимкнено.";
      case POLISH -> "🔕 Monitorowanie wyłączone.";
    };
  }

  public String classUnavailable(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "⚠️ This class is no longer available.";
      case RUSSIAN -> "⚠️ Этот класс больше недоступен.";
      case UKRAINIAN -> "⚠️ Цей клас більше недоступний.";
      case POLISH -> "⚠️ Ta klasa nie jest już dostępna.";
    };
  }

  public String invalidClassControl(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "⚠️ This class control is no longer valid.";
      case RUSSIAN -> "⚠️ Этот список классов больше неактуален.";
      case UKRAINIAN -> "⚠️ Цей список класів більше неактуальний.";
      case POLISH -> "⚠️ Ten wybór klas nie jest już aktualny.";
    };
  }

  public String invalidLanguageControl(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return switch (language) {
      case ENGLISH -> "⚠️ This language control is no longer valid.";
      case RUSSIAN -> "⚠️ Этот выбор языка больше неактуален.";
      case UKRAINIAN -> "⚠️ Цей вибір мови більше неактуальний.";
      case POLISH -> "⚠️ Ten wybór języka nie jest już aktualny.";
    };
  }

  public String baselineNotification(
      TelegramLanguage language,
      String className,
      LocalDate weekStart,
      LocalDate weekEnd,
      int activeChangeCount) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(className, "className must not be null");
    Objects.requireNonNull(weekStart, "weekStart must not be null");
    Objects.requireNonNull(weekEnd, "weekEnd must not be null");

    String startFormatted = weekStart.format(DATE_FORMATTER);
    String endFormatted = weekEnd.format(DATE_FORMATTER);

    return switch (language) {
      case ENGLISH ->
          """
          ✅ Monitoring is ready

          🎓 Class: %s
          📅 Week: %s — %s
          🔎 Active schedule changes: %d

          I'll notify you when something changes."""
              .formatted(className, startFormatted, endFormatted, activeChangeCount);
      case RUSSIAN ->
          """
          ✅ Мониторинг настроен

          🎓 Класс: %s
          📅 Неделя: %s — %s
          🔎 Активных изменений: %d

          Я сообщу, когда в расписании что-то изменится."""
              .formatted(className, startFormatted, endFormatted, activeChangeCount);
      case UKRAINIAN ->
          """
          ✅ Моніторинг налаштовано

          🎓 Клас: %s
          📅 Тиждень: %s — %s
          🔎 Активних змін: %d

          Я повідомлю, коли в розкладі щось зміниться."""
              .formatted(className, startFormatted, endFormatted, activeChangeCount);
      case POLISH ->
          """
          ✅ Monitorowanie jest gotowe

          🎓 Klasa: %s
          📅 Tydzień: %s — %s
          🔎 Aktywne zmiany w planie: %d

          Powiadomię Cię, gdy coś się zmieni."""
              .formatted(className, startFormatted, endFormatted, activeChangeCount);
    };
  }

  public String changeNotification(
      TelegramLanguage language,
      NotificationEventType eventType,
      String className,
      LocalDate lessonDate,
      ChangeType changeType) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(className, "className must not be null");
    Objects.requireNonNull(lessonDate, "lessonDate must not be null");
    Objects.requireNonNull(changeType, "changeType must not be null");

    String heading =
        switch (eventType) {
          case CHANGE_NEW ->
              switch (language) {
                case ENGLISH -> "🔔 New schedule change";
                case RUSSIAN -> "🔔 Новое изменение в расписании";
                case UKRAINIAN -> "🔔 Нова зміна в розкладі";
                case POLISH -> "🔔 Nowa zmiana w planie";
              };
          case CHANGE_UPDATED ->
              switch (language) {
                case ENGLISH -> "✏️ Schedule change updated";
                case RUSSIAN -> "✏️ Изменение в расписании обновлено";
                case UKRAINIAN -> "✏️ Зміну в розкладі оновлено";
                case POLISH -> "✏️ Zmiana w planie została zaktualizowana";
              };
          case CHANGE_RESOLVED ->
              switch (language) {
                case ENGLISH -> "✅ Schedule change is no longer active";
                case RUSSIAN -> "✅ Изменение в расписании больше не актуально";
                case UKRAINIAN -> "✅ Зміна в розкладі більше не актуальна";
                case POLISH -> "✅ Zmiana w planie nie jest już aktualna";
              };
          case BASELINE_ESTABLISHED ->
              throw new IllegalArgumentException("Baseline established uses baselineNotification");
        };

    String dateFormatted = lessonDate.format(DATE_FORMATTER);
    String typeText = changeType(language, changeType);

    return switch (language) {
      case ENGLISH ->
          "%s\n\n🎓 Class: %s\n📅 Date: %s\n🔄 Change: %s"
              .formatted(heading, className, dateFormatted, typeText);
      case RUSSIAN ->
          "%s\n\n🎓 Класс: %s\n📅 Дата: %s\n🔄 Изменение: %s"
              .formatted(heading, className, dateFormatted, typeText);
      case UKRAINIAN ->
          "%s\n\n🎓 Клас: %s\n📅 Дата: %s\n🔄 Зміна: %s"
              .formatted(heading, className, dateFormatted, typeText);
      case POLISH ->
          "%s\n\n🎓 Klasa: %s\n📅 Data: %s\n🔄 Zmiana: %s"
              .formatted(heading, className, dateFormatted, typeText);
    };
  }

  public String changeType(TelegramLanguage language, ChangeType type) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return switch (type) {
      case TEACHER_SUBSTITUTION ->
          switch (language) {
            case ENGLISH -> "Teacher substitution";
            case RUSSIAN -> "Замена учителя";
            case UKRAINIAN -> "Заміна вчителя";
            case POLISH -> "Zastępstwo nauczyciela";
          };
      case UNKNOWN ->
          switch (language) {
            case ENGLISH -> "Other schedule change";
            case RUSSIAN -> "Другое изменение расписания";
            case UKRAINIAN -> "Інша зміна в розкладі";
            case POLISH -> "Inna zmiana w planie";
          };
    };
  }

  public String nativeCommandDescription(TelegramLanguage language, TelegramCommand command) {
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(command, "command must not be null");
    return switch (command) {
      case START ->
          switch (language) {
            case ENGLISH -> "Start / choose language";
            case RUSSIAN -> "Начать / выбрать язык";
            case UKRAINIAN -> "Почати / обрати мову";
            case POLISH -> "Rozpocznij / wybierz język";
          };
      case CONNECT ->
          switch (language) {
            case ENGLISH -> "Connect your VULCAN account";
            case RUSSIAN -> "Подключить аккаунт VULCAN";
            case UKRAINIAN -> "Підключити обліковий запис VULCAN";
            case POLISH -> "Połącz konto VULCAN";
          };
      case CLASSES ->
          switch (language) {
            case ENGLISH -> "Choose classes to monitor";
            case RUSSIAN -> "Выбрать классы для отслеживания";
            case UKRAINIAN -> "Обрати класи для відстеження";
            case POLISH -> "Wybierz klasy do monitorowania";
          };
      case SUBSCRIPTIONS ->
          switch (language) {
            case ENGLISH -> "View monitored classes";
            case RUSSIAN -> "Показать отслеживаемые классы";
            case UKRAINIAN -> "Показати відстежувані класи";
            case POLISH -> "Pokaż monitorowane klasy";
          };
      case STATUS ->
          switch (language) {
            case ENGLISH -> "Check connection and monitoring status";
            case RUSSIAN -> "Проверить состояние подключения";
            case UKRAINIAN -> "Перевірити стан підключення";
            case POLISH -> "Sprawdź stan połączenia";
          };
      case LANGUAGE ->
          switch (language) {
            case ENGLISH -> "Change language";
            case RUSSIAN -> "Изменить язык";
            case UKRAINIAN -> "Змінити мову";
            case POLISH -> "Zmień język";
          };
      case HELP ->
          switch (language) {
            case ENGLISH -> "Show all commands";
            case RUSSIAN -> "Показать все команды";
            case UKRAINIAN -> "Показати всі команди";
            case POLISH -> "Pokaż wszystkie polecenia";
          };
    };
  }
}
