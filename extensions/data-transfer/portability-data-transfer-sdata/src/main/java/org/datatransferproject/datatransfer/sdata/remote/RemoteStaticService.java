/*
 * Copyright 2025 The Data Transfer Project Authors.
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

package org.datatransferproject.datatransfer.sdata.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.util.Optional;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.sdata.common.JobService;
import org.datatransferproject.datatransfer.sdata.common.SdataCredentialFactory;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaAlbum;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaAlbumListResponse;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaItem;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaItemSearchResponse;
import org.datatransferproject.datatransfer.sdata.takeout.TakeoutMediaReader;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.transfer.types.CopyExceptionWithFailureReason;

// TODO (#1307): Find a way to consolidate all 3P API interfaces
public class RemoteStaticService {
  /** Basename we expect a developer to use when saving a fake/test-user's Takeout zip into. */
  // TODO: sdata optimization: this is hardcoded for media, and further is tightly coupling this
  // class to a local filesystem redesign int he future to pull from remote store (like GCP bucket)
  // for Zips that are very large, and thus better managed as a team's collection of fakes.
  private static final String SDATA_BASENAME_MEDIA_ZIP = "takeout-for-media.zip";

  private static final String GOOGPHOTOS_ALBUMS_PERMISSION_ERROR =
      "The caller does not have permission";
  private static final String GOOGPHOTOS_PHOTO_PERMISSION_ERROR =
      "Google Photos is disabled for the user";

  private static final String BASE_URL = "https://photoslibrary.googleapis.com/v1/";
  private static final int ALBUM_PAGE_SIZE = 20; // TODO
  private static final int MEDIA_PAGE_SIZE = 50; // TODO

  private static final String PAGE_SIZE_KEY = "pageSize";
  private static final String TOKEN_KEY = "pageToken";
  private static final String ALBUM_ID_KEY = "albumId";
  private static final String ACCESS_TOKEN_KEY = "access_token";
  private static final String FILTERS_KEY = "filters";
  private static final String INCLUDE_ARCHIVED_KEY = "includeArchivedMedia";

  private final ObjectMapper objectMapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final HttpTransport httpTransport = new NetHttpTransport();
  private Credential credential;
  private final JsonFactory jsonFactory;
  private final Monitor monitor;
  private final SdataCredentialFactory credentialFactory;
  private final RateLimiter readRateLimiter;

  private final SdataReader<
          SdataMediaAlbum,
          SdataMediaAlbumListResponse,
          SdataMediaItem,
          SdataMediaItemSearchResponse>
      takeoutMediaReader;

  public RemoteStaticService(
      SdataCredentialFactory credentialFactory,
      Credential credential,
      JsonFactory jsonFactory,
      Monitor monitor,
      double readsPerSecond,
      JobService<JobStore> perJobStore)
      throws IOException, CopyExceptionWithFailureReason {
    this.credentialFactory = credentialFactory;
    this.credential = credential;
    this.jsonFactory = jsonFactory;
    this.monitor = monitor;
    readRateLimiter = RateLimiter.create(readsPerSecond);

    // TODO: sdata optimization: have this be a bucket reader in the future that expects the zip
    // (inflated or otherwise)
    // in a remote store.
    this.takeoutMediaReader = new TakeoutMediaReader(SDATA_BASENAME_MEDIA_ZIP, perJobStore);
  }

  /** Lists all albums in a user's library. */
  public SdataMediaAlbumListResponse listAlbums(Optional<String> pageToken)
      throws IOException, CopyExceptionWithFailureReason {
    return takeoutMediaReader.listContainers(pageToken);
  }

  public SdataMediaAlbum getAlbum(String albumId)
      throws IOException, CopyExceptionWithFailureReason {
    return takeoutMediaReader.getContainer(albumId);
  }

  public SdataMediaItem getMediaItem(String mediaId)
      throws IOException, CopyExceptionWithFailureReason {
    return takeoutMediaReader.getItem(mediaId);
  }

  /**
   * Lists items in a album or in the albumless-root of the user's account, if no albumId is
   * specified.
   *
   * <p>Empty albumId means we're listing from the albumless-root of the user's account.
   */
  public SdataMediaItemSearchResponse listMediaItems(
      Optional<String> albumId, Optional<String> pageToken)
      throws IOException, CopyExceptionWithFailureReason {
    return takeoutMediaReader.listItems(albumId, pageToken);
  }
}
