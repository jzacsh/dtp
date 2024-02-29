package org.datatransferproject.datatransfer.apple.exceptions;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Very basic exception metadata one might need when debugging {@link java.net.HttpURLConnection}
 * error states.
 */
public class HttpException extends Exception {
  // For internal use; matches HttpURLConnection's getResponseCode() API:
  // > Returns -1 if no code can be discerned
  private static final int EXCEPTION_READING_HTTP_RESPONSE_CODE = -1;

  private int responseCode;
  private String url;
  private String method;

  /**
   * Generate an exception containing HTTP response code, `detailMessage` and server's response
   * body.
   *
   * @param HttpUrlConnection that is in an error saate
   * @param detailMessage simplified message indicating what was being attempted and seems wrong.
   */
  public HttpException(HttpURLConnection conn, String detailMessage) {
    this(
        safeResponseCode(conn),
        conn.getURL().toString(),
        conn.getRequestMethod(),
        detailMessage + ": " + safeResponseMessage(conn));
  }

  public HttpException(HttpURLConnection conn, String detailMessage, Throwable cause) {
    this(
        safeResponseCode(conn),
        conn.getURL().toString(),
        conn.getRequestMethod(),
        detailMessage + ": " + safeResponseMessage(conn),
        cause);
  }

  private HttpException(int responseCode, String url, String method, String message) {
    super(buildDetailMessage(responseCode, url, method, message));
    this.url = url;
    this.method = method;
    this.responseCode = responseCode;
  }

  private HttpException(
      int responseCode, String url, String method, String message, Throwable cause) {
    super(buildDetailMessage(responseCode, url, method, message), cause);
    this.url = url;
    this.method = method;
    this.responseCode = responseCode;
  }

  private static String buildDetailMessage(
      int responseCode, String url, String method, String message) {
    String responseCodeMsg =
        responseCode == EXCEPTION_READING_HTTP_RESPONSE_CODE
            ? "[IOException reading response code]"
            : String.valueOf(responseCode);
    return String.format(
        "HTTP response code %s encountered with response body:\n%s\n%s\n%s\n",
        responseCodeMsg, url, "\"\"\"", message, "\"\"\"");
  }

  private static int safeResponseCode(HttpURLConnection conn) {
    try {
      return conn.getResponseCode();
    } catch (IOException e) {
      return EXCEPTION_READING_HTTP_RESPONSE_CODE;
    }
  }

  /**
   * Safely unwraps a useful msessage from {@link HttpURLConnection#getResponseMessage} according to
   * its own API docs.
   */
  private static String safeResponseMessage(HttpURLConnection conn) {
    String responseMessage = null;
    try {
      responseMessage = conn.getResponseMessage();
    } catch (IOException e) {
      return "[IOException reading response message]";
    }
    return responseMessage == null
        ? "[invalid HTTP response, message indiscernible]"
        : responseMessage;
  }
}
