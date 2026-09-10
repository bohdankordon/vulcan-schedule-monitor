package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

public enum SessionCookieCountBucket {
  ZERO,
  ONE,
  TWO,
  THREE,
  FOUR,
  FIVE,
  SIX,
  SEVEN,
  EIGHT_PLUS;

  public static SessionCookieCountBucket fromCount(int count) {
    if (count <= 0) {
      return ZERO;
    }
    return switch (count) {
      case 1 -> ONE;
      case 2 -> TWO;
      case 3 -> THREE;
      case 4 -> FOUR;
      case 5 -> FIVE;
      case 6 -> SIX;
      case 7 -> SEVEN;
      default -> EIGHT_PLUS;
    };
  }
}
