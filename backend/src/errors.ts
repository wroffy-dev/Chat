/**
 * Error codes from docs/14-api-contracts.md §1. Clients branch on `code`; `message` is for humans.
 */
export type ErrorCode =
  | "BAD_REQUEST"
  | "VALIDATION_FAILED"
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "METHOD_NOT_ALLOWED"
  | "CONFLICT"
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

/** An error whose code and message are safe to show to the client. */
export class AppError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: ErrorCode,
    message: string,
    public readonly details?: ErrorDetail[],
  ) {
    super(message);
    this.name = "AppError";
  }
}

export const DEFAULT_MESSAGES: Record<ErrorCode, string> = {
  BAD_REQUEST: "The request could not be read.",
  VALIDATION_FAILED: "The request is not valid.",
  UNAUTHENTICATED: "Authentication is required.",
  FORBIDDEN: "You are not allowed to do this.",
  NOT_FOUND: "Not found.",
  METHOD_NOT_ALLOWED: "Method not allowed.",
  CONFLICT: "The request conflicts with the current state.",
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
