package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.VulcanConnectLinkService;
import java.util.Objects;

public final class ConnectCommandHandler implements TelegramCommandHandler {

  private final VulcanConnectLinkService links;

  public ConnectCommandHandler() {
    this(appUserId -> ConnectLink.disabled());
  }

  public ConnectCommandHandler(VulcanConnectLinkService links) {
    this.links = Objects.requireNonNull(links, "links must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.CONNECT;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    ConnectLink link = links.issue(context.appUserId());
    if (!link.enabled()) {
      return TelegramTexts.CONNECT_DISABLED;
    }
    return "Open this short-lived, single-use HTTPS link to connect VULCAN:\n"
        + link.url()
        + "\nNever send VULCAN credentials through Telegram.";
  }
}
