/**
 * Turns a workflow error (RFC 7807-like: type, status, title, detail) into words a person can read.
 * The raw title often holds the body the called server sent back (an HTML page, a stack trace):
 * it is never shown as the headline, only in the raw payload.
 */

export interface RawError {
  type?: string;
  status?: number;
  title?: string;
  detail?: string;
  instance?: string;
}

export interface ReadableError {
  /** "HTTP 403 Forbidden", "Validation error". */
  headline: string;
  /** "The called server refused access." */
  explanation: string;
}

const REASON_PHRASES: Record<number, string> = {
  400: "Bad Request",
  401: "Unauthorized",
  402: "Payment Required",
  403: "Forbidden",
  404: "Not Found",
  405: "Method Not Allowed",
  406: "Not Acceptable",
  408: "Request Timeout",
  409: "Conflict",
  410: "Gone",
  412: "Precondition Failed",
  413: "Payload Too Large",
  415: "Unsupported Media Type",
  422: "Unprocessable Content",
  429: "Too Many Requests",
  500: "Internal Server Error",
  501: "Not Implemented",
  502: "Bad Gateway",
  503: "Service Unavailable",
  504: "Gateway Timeout",
};

const EXPLANATIONS: Record<number, string> = {
  400: "The called server rejected the request as malformed",
  401: "The called server asked for credentials",
  403: "The called server refused access",
  404: "The called server found nothing at this address",
  405: "The called server does not accept this method",
  408: "The called server gave up waiting for the request",
  409: "The called server reported a conflict",
  422: "The called server could not process the data sent",
  429: "The called server is limiting requests",
  500: "The called server failed while handling the request",
  501: "The called server does not support this request",
  502: "A gateway in front of the called server failed",
  503: "The called server is unavailable",
  504: "A gateway in front of the called server timed out",
};

const TYPE_LABELS: Record<string, string> = {
  communication: "Communication error",
  validation: "Validation error",
  expression: "Expression error",
  timeout: "Timeout",
  authentication: "Authentication error",
  authorization: "Authorization error",
  configuration: "Configuration error",
  runtime: "Runtime error",
};

export function reasonPhrase(status: number): string {
  return REASON_PHRASES[status] ?? "";
}

function typeKey(type?: string): string {
  if (!type) return "";
  const last = type.split(/[/#]/).filter(Boolean).pop() ?? "";
  return last.toLowerCase();
}

function looksLikeMarkup(value: string): boolean {
  const trimmed = value.trim().toLowerCase();
  return trimmed.startsWith("<") || trimmed.includes("<!doctype") || trimmed.includes("<html");
}

/** A short, human title from a raw error title, or "" when it is not readable. */
function cleanTitle(title?: string): string {
  if (!title) return "";
  if (looksLikeMarkup(title)) return "";
  // "HTTP 403: <body…>" → "", "Forbidden" → "Forbidden"
  const withoutHttpPrefix = title.replace(/^HTTP\s*\d{3}\s*:?\s*/i, "");
  if (looksLikeMarkup(withoutHttpPrefix)) return "";
  const oneLine = withoutHttpPrefix.split("\n")[0].trim();
  return oneLine.length > 120 ? `${oneLine.slice(0, 117)}…` : oneLine;
}

export function readableError(error: RawError | undefined): ReadableError {
  if (!error) return { headline: "Faulted", explanation: "" };
  const status = error.status && error.status > 0 ? error.status : undefined;
  const key = typeKey(error.type);
  const title = cleanTitle(error.title);
  const detail = cleanTitle(error.detail);

  // An HTTP headline for errors of a call: typed as communication, titled with the HTTP reason
  // phrase or "HTTP …", or with no readable title. A raised error keeps its own title.
  const rawTitle = (error.title ?? "").trim();
  const httpLike =
    key === "communication" ||
    /^HTTP\b/i.test(rawTitle) ||
    (status !== undefined && rawTitle.toLowerCase() === reasonPhrase(status).toLowerCase()) ||
    (!title && status !== undefined && Boolean(REASON_PHRASES[status]));
  if (status && status >= 100 && status < 600 && httpLike) {
    const phrase = reasonPhrase(status) || title;
    return {
      headline: `HTTP ${status}${phrase ? ` ${phrase}` : ""}`,
      explanation: EXPLANATIONS[status] ?? (status >= 500 ? "The called server failed" : "The called server rejected the request"),
    };
  }

  const typeLabel = TYPE_LABELS[key] ?? (key ? capitalize(key.replace(/[-_]/g, " ")) : "");
  if (title) {
    const explanation = detail || typeLabel;
    return { headline: title, explanation: explanation.toLowerCase() === title.toLowerCase() ? "" : explanation };
  }
  return { headline: typeLabel || "Faulted", explanation: detail };
}

function capitalize(value: string): string {
  return value.length === 0 ? value : value[0].toUpperCase() + value.slice(1);
}
