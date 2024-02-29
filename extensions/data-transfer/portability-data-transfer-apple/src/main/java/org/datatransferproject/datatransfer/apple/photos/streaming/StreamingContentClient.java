/*
 * Copyright 2023 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.datatransfer.apple.photos.streaming;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import java.net.ProtocolException;
import org.apache.http.HttpStatus;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.apple.constants.AppleConstants;
import org.datatransferproject.datatransfer.apple.constants.ApplePhotosConstants;
import org.datatransferproject.datatransfer.apple.constants.Headers;
import org.datatransferproject.datatransfer.apple.exceptions.HttpException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

/**
 * An Http Client to handle uploading and downloading of the streaming content.
 *
 * <p>Warning: like any closeable ensure callsites cleanup (eg: use try-with-resources statements).
 */
// TODO switch internals to something a bit less low-level than HttpUrlConnection+IOUtils, like say
// org.apache.http.client offerings. See
// https://hc.apache.org/httpcomponents-client-5.3.x/quickstart.html for how.
public class StreamingContentClient implements Closeable {
  private HttpURLConnection connection;
  private DataOutputStream outputStream;

  private Monitor monitor;

  public enum StreamingMode {
    UPLOAD,
    DOWNLOAD
  };

  /**
   * Creates a streaming session with the specified URL.
   *
   * @param url the url to upload or download from
   * @param mode indicates if this is an upload or a download session
   * @throws IOException
   */
  public StreamingContentClient(
      @NotNull final URL url, @NotNull final StreamingMode mode, @NotNull Monitor monitor)
      throws HttpException {
    this.monitor = monitor;
    try {
      connection = (HttpURLConnection) url.openConnection();
    } catch (IOException e) {
      throw new HttpException(connection, String.format("[mode=%s] failed opening connection to server", mode), e);
    }

    connection.setRequestProperty("Transfer-Encoding", "chunked");
    connection.setRequestProperty("content-type", "application/octet-stream");
    connection.setDoOutput(true);
    if (mode.equals(StreamingMode.UPLOAD)) {
      connection.setDoInput(true);
      connection.setChunkedStreamingMode(ApplePhotosConstants.contentRequestLength);
      setValidHttpMethod(connection, ValidHttpMethod.POST);
      connection.setRequestProperty(Headers.OPERATION_GROUP.getValue(), AppleConstants.DTP_IMPORT_OPERATION_GROUP);

      OutputStream connectionOutputStream;
      try {
        connectionOutputStream = connection.getOutputStream();
      } catch (IOException e) {
        throw new HttpException(connection, String.format("[mode=%s] failed creating output stream to write to server", mode), e);
      }
      outputStream = new DataOutputStream(connectionOutputStream);
    } else {
      setValidHttpMethod(connection, ValidHttpMethod.GET);
    }
  }

  private enum ValidHttpMethod {
    GET,
    POST
  }

  private static void setValidHttpMethod(HttpURLConnection connection, ValidHttpMethod method) throws IllegalStateException {
    try {
      connection.setRequestMethod(method.name());
    } catch (ProtocolException e) {
      throw new IllegalStateException(String.format("failed setting %s as HTTP method", method.name()), e);
    }
  }

  /**
   * Uploads the given bytes to the url specified in the constructor. If lastRequest is true, the
   * upload is complete and a ContentResponse is returned. Otherwise, null is returned.
   *
   * @param uploadBytes
   * @return ContentResponse
   * @throws HttpException
   */
  @Nullable
  public void uploadBytes(@NotNull final byte[] uploadBytes) throws HttpException {
    try {
      outputStream.write(uploadBytes);
    } catch (IOException e) {
      monitor.severe(() -> "Error when uploading to content", e);
      throw new HttpException(connection, "uploading content to Apple", e);
    }
  }

  @Override
  public void close() {
    connection.disconnect();
  }

  @Nullable
  public String completeUpload() throws HttpException {
    try {
      StringBuilder content;
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
        String line;
        content = new StringBuilder();
        while ((line = br.readLine()) != null) {
          content.append(line);
          content.append(System.lineSeparator());
        }
      }
      return content.toString();
    } catch (IOException e) {
      monitor.severe(() -> "Error when completing upload", e);
      throw new HttpException(connection, "completing content-upload to Apple", e);
    }
  }

  /**
   * Attempts to read the given number of bytes from the url specified in the constructor. If less
   * than the given number of bytes are available, return a truncated buffer. If no bytes are
   * available, return null.
   *
   * @param maxBytesToRead
   * @return bytes read from url (or null if none can be read)
   * @throws HttpException
   */
  @Nullable
  public byte[] downloadBytes(final int maxBytesToRead) throws HttpException {
    final byte[] buffer = new byte[maxBytesToRead];

    try (InputStream connInputStream = connection.getInputStream()) {
      int bytesRead = IOUtils.read(connInputStream, buffer);
      // retry if a redirect is received, otherwise throw an exception
      // TODO we can probably delete this branch since HttpUrlConnection#setFollowRedirects is
      // enabled by default.
      if (connection.getResponseCode() != HttpStatus.SC_OK) {
        if (connection.getResponseCode() == HttpStatus.SC_MOVED_PERMANENTLY ||
            connection.getResponseCode() == HttpStatus.SC_MOVED_TEMPORARILY) {
          final String newUrl = connection.getHeaderField(HttpHeaders.LOCATION);
          URL urlObject = new URL(newUrl);
          connection = (HttpURLConnection) urlObject.openConnection();
          connection.setRequestProperty("Transfer-Encoding", "chunked");
          connection.setRequestProperty("content-type", "application/octet-stream");
          connection.setDoOutput(true);
          connection.setRequestMethod("GET");


          try (InputStream redirectedConnInputStream = connection.getInputStream()) {
            bytesRead = IOUtils.read(redirectedConnInputStream, buffer);

            if (connection.getResponseCode() != HttpStatus.SC_OK) {
              throw new HttpException(
                  connection, "downloading redirected transfer content: non-200 HTTP response code");
            }
          }
        } else {
          throw new HttpException(
              connection, "downloading transfer content: non-200 HTTP response code");
        }
      }
      if (bytesRead < maxBytesToRead) {
        if (bytesRead <= 0) {
          return null;
        } else {
          final byte[] truncatedBuffer = Arrays.copyOf(buffer, bytesRead);
          return truncatedBuffer;
        }
      }
      return buffer;
    } catch (IOException e) {
      monitor.severe(() -> "Error when downloading content", e);
      throw new HttpException(connection, "downloading content", e);
    }
  }
}
