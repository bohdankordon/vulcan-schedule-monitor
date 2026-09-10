package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

public enum SetCookieCount {
  ZERO,
  ONE,
  TWO,
  THREE_PLUS;

  public static SetCookieCount fromCount(int count) {
    if (count <= 0) {
      return ZERO;
    }
    return switch (count) {
      case 1 -> ONE;
      case 2 -> TWO;
      default -> THREE_PLUS;
    };
  }
}
