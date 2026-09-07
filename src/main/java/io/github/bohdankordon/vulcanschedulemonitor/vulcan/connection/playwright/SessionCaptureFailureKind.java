package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

enum SessionCaptureFailureKind {
  NOT_APPLICABLE,
  NO_ALLOWED_REQUEST,
  NO_COMPLETE_REQUEST,
  NO_MATCHING_COOKIES,
  APPLICATION_BASE_REJECTED,
  REFERER_REJECTED,
  MATERIAL_REJECTED,
  OTHER_PROTOCOL_FAILURE
}
