package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.VulcanConnectLinkService;
import java.util.Objects;

public final class ConnectCommandHandler implements TelegramCommandHandler {

  private final VulcanConnectLinkService links;
  private final TelegramTextCatalog textCatalog;

  public ConnectCommandHandler() {
    this(appUserId -> ConnectLink.disabled(), new TelegramTextCatalog());
  }

  public ConnectCommandHandler(VulcanConnectLinkService links) {
    this(links, new TelegramTextCatalog());
  }

  public ConnectCommandHandler(VulcanConnectLinkService links, TelegramTextCatalog textCatalog) {
    this.links = Objects.requireNonNull(links, "links must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.CONNECT;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    ConnectLink link = links.issue(context.appUserId());
    if (!link.enabled()) {
      return textCatalog.connectDisabled(context.language());
    }
    return textCatalog.connectLink(context.language(), link.url());
  }
}
