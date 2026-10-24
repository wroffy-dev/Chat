/**
 * Error codes from docs/14-api-contracts.md §1. Clients branch on `code`; `message` is for humans.
 */
export type ErrorCode =
  | "BAD_REQUEST"
  | "VALIDATION_FAILED"
  | "UNAUTHENTICATED"
  | "INVALID_CREDENTIALS"
  | "TOKEN_EXPIRED"
  | "SESSION_REVOKED"
  | "FORBIDDEN"
  | "ACCOUNT_DISABLED"
  | "PASSWORD_CHANGE_REQUIRED"
  | "WEAK_PASSWORD"
  | "NOT_FOUND"
  | "METHOD_NOT_ALLOWED"
  | "CONFLICT"
  | "ALREADY_CONNECTED"
  | "REQUEST_ALREADY_PENDING"
  | "REQUEST_NOT_PENDING"
  | "QR_INVALID_OR_EXPIRED"
  | "CALL_IN_PROGRESS"
  | "CALL_NOT_ACTIVE"
  | "MEDIA_UNAVAILABLE"
  | "PAYLOAD_TOO_LARGE"
  | "UNSUPPORTED_MEDIA_TYPE"
  | "RATE_LIMITED"
  | "INTERNAL_ERROR"
  | "SERVICE_UNAVAILABLE";

export interface ErrorDetail {
  path: string;
  rule: string;
}

export interface ErrorBody {
  error: {
    code: ErrorCode;
    message: string;
    requestId: string;
    details?: ErrorDetail[];
  };
}

export function appError(statusCode: number, code: ErrorCode, details?: ErrorDetail[]): AppError {
  return new AppError(statusCode, code, DEFAULT_MESSAGES[code], details);
}

/** An error whose code and message are safe to show to the client. */
export class AppError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: ErrorCode,
    message: string,
    public readonly details?: ErrorDetail[],
    /** Sent as the Retry-After header (seconds) for 429 responses. */
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "AppError";
  }
}

export const DEFAULT_MESSAGES: Record<ErrorCode, string> = {
  BAD_REQUEST: "The request could not be read.",
  VALIDATION_FAILED: "The request is not valid.",
  UNAUTHENTICATED: "Authentication is required.",
  INVALID_CREDENTIALS: "Username or password is incorrect.",
  TOKEN_EXPIRED: "Your session token has expired. Refresh it and try again.",
  SESSION_REVOKED: "Your session has ended. Log in again.",
  FORBIDDEN: "You are not allowed to do this.",
  ACCOUNT_DISABLED: "This account is disabled.",
  PASSWORD_CHANGE_REQUIRED: "You must change your password before continuing.",
  WEAK_PASSWORD: "Choose a longer or less common password.",
  NOT_FOUND: "Not found.",
  METHOD_NOT_ALLOWED: "Method not allowed.",
  CONFLICT: "The request conflicts with the current state.",
  ALREADY_CONNECTED: "You are already connected.",
  REQUEST_ALREADY_PENDING: "A connection request is already waiting for an answer.",
  REQUEST_NOT_PENDING: "This request has already been answered or has expired.",
  QR_INVALID_OR_EXPIRED: "This code is no longer valid. Ask for a new one.",
  CALL_IN_PROGRESS: "A call is already in progress.",
  CALL_NOT_ACTIVE: "This call is no longer ringing or active.",
  MEDIA_UNAVAILABLE: "Calling is temporarily unavailable.",
  PAYLOAD_TOO_LARGE: "The request is too large.",
  UNSUPPORTED_MEDIA_TYPE: "Unsupported content type.",
  RATE_LIMITED: "Too many requests. Try again later.",
  INTERNAL_ERROR: "Something went wrong.",
  SERVICE_UNAVAILABLE: "The service is temporarily unavailable.",
};

/** Maps a client-error HTTP status (from Fastify or a plugin) to a stable public code. */
export function codeForStatus(statusCode: number): ErrorCode {
  switch (statusCode) {
    case 400:
      return "BAD_REQUEST";
    case 401:
      return "UNAUTHENTICATED";
    case 403:
      return "FORBIDDEN";
    case 404:
      return "NOT_FOUND";
    case 405:
      return "METHOD_NOT_ALLOWED";
    case 409:
      return "CONFLICT";
    case 413:
      return "PAYLOAD_TOO_LARGE";
    case 415:
      return "UNSUPPORTED_MEDIA_TYPE";
    case 429:
      return "RATE_LIMITED";
    case 503:
      return "SERVICE_UNAVAILABLE";
    default:
      return statusCode >= 500 ? "INTERNAL_ERROR" : "BAD_REQUEST";
  }
}
