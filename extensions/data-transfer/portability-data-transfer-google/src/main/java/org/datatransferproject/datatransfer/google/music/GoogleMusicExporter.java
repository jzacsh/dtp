/*
 * Copyright 2022 The Data Transfer Project Authors.
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

package org.datatransferproject.datatransfer.google.music;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.JsonFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.google.common.GoogleCredentialFactory;
import org.datatransferproject.datatransfer.google.musicModels.GooglePlaylist;
import org.datatransferproject.datatransfer.google.musicModels.GooglePlaylistItem;
import org.datatransferproject.datatransfer.google.musicModels.GoogleRelease;
import org.datatransferproject.datatransfer.google.musicModels.GoogleTrack;
import org.datatransferproject.datatransfer.google.musicModels.PlaylistItemListResponse;
import org.datatransferproject.datatransfer.google.musicModels.PlaylistListResponse;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.provider.ExportResult.ResultType;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.spi.transfer.types.PermissionDeniedException;
import org.datatransferproject.types.common.ExportInformation;
import org.datatransferproject.types.common.PaginationData;
import org.datatransferproject.types.common.StringPaginationToken;
import org.datatransferproject.types.common.models.IdOnlyContainerResource;
import org.datatransferproject.types.common.models.music.MusicContainerResource;
import org.datatransferproject.types.common.models.music.MusicGroup;
import org.datatransferproject.types.common.models.music.MusicPlaylist;
import org.datatransferproject.types.common.models.music.MusicPlaylistItem;
import org.datatransferproject.types.common.models.music.MusicRecording;
import org.datatransferproject.types.common.models.music.MusicRelease;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

public class GoogleMusicExporter implements Exporter<TokensAndUrlAuthData, MusicContainerResource> {
  /* DO NOT MERGE document this and explain the DTP codebase's practice of passing a paging api's own token
   * prefixed with DTP's own critical information (what datatype we're working with at the moment).
   *
   * Also leave a TODO to maek this better (primitive obsession, galore).
   */
  // DO NOT MERGE: check with siyug that we *really* want these strings; we probably _don't_ (ie:
  // we're probably never _actually_ stripping back chunk by chunk (eg: taking a
  // "playlist:track:release:", stripping the tail off and continuing on automatically with a
  // "playlist:track:" instead), therefore we can just rely on Enum::toString() and we'll be
  // utilizing "PLAYLIST_TRACK_RELEASE" as a string instead.
  public enum TokenPrefix {
    PLAYLIST_TRACK_RELEASE("playlist:track:release:"),
    PLAYLIST_TRACK("playlist:track:"),
    PLAYLIST_ITEM("playlist:item:"), // DO NOT MERGE; siyug@: correct?
    PLAYLIST_RELEASE("playlist:release:"),
    TRACK_RELEASE("track:release:"),
    PLAYLIST("playlist:"),
    TRACK("track:"),
    RELEASE("release:"),
    UNKNOWN("") // invalid prefix
      ;

    final private String prefix;
    TokenPrefix(String prefix) {
      this.prefix = prefix;
    }

    public static TokenPrefix of(String needle) {
      for (TokenPrefix prefix : TokenPrefix.values()) {
        if (prefix.toString().equals(needle)) {
          return prefix;
        }
      }
      return TokenPrefix.UNKNOWN;
    }

    public String toString() {
      return prefix;
    }

    public static ImmutableSet<String> knownPrefixes() {
      return ImmutableSet.copyOf(Arrays.stream(TokenPrefix.values()).map(v -> v.toString()).collect(Collectors.toSet()));
    }
  }

  private static Optional<TransferPageInfo<TokenPrefix>> toTransferPageInfo(PaginationData paginationData) {
    return TransferPageInfo.of(paginationData, (pageData) -> {
      TokenPrefix prefix = TokenPrefix.of(pageData);
      Preconditions.checkState(prefix != TokenPrefix.UNKNOWN, "invalid token prefix: '%s'", pageData);
      return prefix;
    });
  }


  // DO NOT MERGE - this is an inheret property of GooglePlaylist class and belongs over there;
  // remove references to here and use that class's export instead.
  static final String GOOGLE_PLAYLIST_NAME_PREFIX = "playlists/";

  private final GoogleCredentialFactory credentialFactory;
  private final JsonFactory jsonFactory;
  private volatile GoogleMusicHttpApi musicHttpApi;

  private final Monitor monitor;

  public GoogleMusicExporter(
      GoogleCredentialFactory credentialFactory, JsonFactory jsonFactory, Monitor monitor) {
    this.credentialFactory = credentialFactory;
    this.jsonFactory = jsonFactory;
    this.monitor = monitor;
  }

  @VisibleForTesting
  GoogleMusicExporter(
      GoogleCredentialFactory credentialFactory,
      JsonFactory jsonFactory,
      GoogleMusicHttpApi musicHttpApi,
      Monitor monitor) {
    this.credentialFactory = credentialFactory;
    this.jsonFactory = jsonFactory;
    this.musicHttpApi = musicHttpApi;
    this.monitor = monitor;
  }

  @Override
  public ExportResult<MusicContainerResource> export(
      UUID jobId, TokensAndUrlAuthData authData, Optional<ExportInformation> exportInformation)
      throws IOException, InvalidTokenException, PermissionDeniedException {
    // DO NOT MERGE - why would exportInformation.isEmpty() be true? handle that case

    Optional<TransferPageInfo<TokenPrefix>> transferPageInfo = toTransferPageInfo(exportInformation.get().getPaginationData());

    // DO NOT MERGE: siyu: do we still need to use instance checks like this? use the swiwtch
    // statement below instead?
    if (exportInformation.get().getContainerResource() instanceof IdOnlyContainerResource) {
      // if ExportInformation is an id only container, this is a request to export playlist items.
      return exportPlaylistItems(
          authData,
          (IdOnlyContainerResource) exportInformation.get().getContainerResource(),
          transferPageInfo);
    }

    if (transferPageInfo.isEmpty()) {
        return emptyExport(); // DO NOT MERGE: is this really possible? taken from siyug's last else block
    }

    switch (transferPageInfo.get().getResourceType()) {
      case PLAYLIST:
        return exportPlaylists(jobId, authData, transferPageInfo.get());
      case TRACK:
        throw new UnsupportedOperationException("track exports not yet implemented");
      case RELEASE:
        throw new UnsupportedOperationException("release exports not yet implemented");
      case UNKNOWN:
        throw new IllegalStateException(String.format(
            "bad pagination token found: '%s'",
            transferPageInfo.get().getSerializedOriginal()));
      default:
        // There is nothing to export.
        return emptyExport(); // DO NOT MERGE: is this really possible? taken from siyug's last else block
    }
  }

  private static ExportResult<MusicContainerResource> emptyExport() {
    return new ExportResult<>(ResultType.END, null, null);
  }

  private ExportResult<MusicContainerResource> exportPlaylists(
      UUID jobId, TokensAndUrlAuthData authData, TransferPageInfo pageInfo)
      throws IOException, InvalidTokenException, PermissionDeniedException {
    Preconditions.checkArgument(
        pageInfo.getResourceType().equals(TokenPrefix.PLAYLIST),
        "Invalid resource type ('%s') found in pagination data ('%s')",
        pageInfo.getResourceType(),
        pageInfo.getSerializedOriginal());

    PlaylistListResponse playlistListResponse =
        getOrCreateMusicHttpApi(authData).listPlaylists(pageInfo.getApiPagingToken());

    ImmutableSet<MusicPlaylist> playlists = GooglePlaylist.toMusicPlaylists(playlistListResponse.getPlaylists());
    final String nextPageToken = playlistListResponse.getNextPageToken();

    // DO NOT MERGE; assuming I understood siyug's original implementation correctly (see truth
    // table below) then DTP really needs to write wrappers for ExportResult (and consider making
    // all ExportResult constructors private) that will build for these cases correctly; eg:
    // - have a token _and_ have data? call and return ExportResult.of(C extends ContainerResource, TransferPage);
    // - have no token but have data? call and return ExportResult.of(C extends ContainerResource);
    // - have no data (and obviously no token)? call and return ExportResult.of();
    /**
     * DO NOT MERGE; run this by siyug
     *
     *  havePlaylistItemsToTransfer
     *     :
     *     :                        haveMorePlaylistsToTransfer
     *     :                          :
     *     :                          :
     *     v                          v
     * this request has playlists  | have more playlists  |  valid? | explanation
     * to report for transfer      | to list              |         |
     * =========================== |===================== |  ====== | ============
     * true                        |   true               |    Y    | current request's happy path; and another request needed for more
     * true                        |   false              |    Y    | current request's happy path; but no more requests needed
     * false                       |   true               |    N    | nonsensical; if no results but
     *                             :                      :         : page token for _more_ results
     *                             :                      :         : than we have should've gotten
     *                             :                      :         : results this time
     * false                       |   false              |    Y    | no more processing needed
     */
    final boolean havePlaylistItemsToTransfer = !playlists.isEmpty(); // "item" is an individual playlist in GMusic parlance
    final boolean haveMorePlaylistsToTransfer = !Strings.isNullOrEmpty(nextPageToken);
    Preconditions.checkState(
        havePlaylistItemsToTransfer || !haveMorePlaylistsToTransfer,
        "invalid api response: no playlists but yet have paging token to fetch more, per zero-length playlists response and paginationData='%s'",
        pageInfo.getSerializedOriginal());

    if (!havePlaylistItemsToTransfer && !haveMorePlaylistsToTransfer) {
      return new ExportResult<>(ResultType.END);
    }
    Preconditions.checkState(
        havePlaylistItemsToTransfer,
        "programmer error: sub-container processing occurring without sub resources");

    ContinuationData continuationData = haveMorePlaylistsToTransfer
        ? new ContinuationData(pageInfo.toNewStringToken(nextPageToken))
        : new ContinuationData(null);

    // Add all newly listed playlist items' IDs to continuation data
    playlists.stream().forEach((playlist) -> {
      continuationData.addContainerResource(new IdOnlyContainerResource(playlist.getId()));
      monitor.debug(  // DO NOT MERGE - do we need this log line?
          () ->
              String.format(
                  "%s: Google Music exporting playlist: %s", jobId, playlist.getId()));
    });
    MusicContainerResource containerResource =
        new MusicContainerResource(playlists, null, null, null);
    return new ExportResult<>(ResultType.CONTINUE, containerResource, continuationData);
  }

  private ExportResult<MusicContainerResource> exportPlaylistItems(
      TokensAndUrlAuthData authData,
      IdOnlyContainerResource playlistData,
      Optional<TransferPageInfo<TokenPrefix>> pageInfo)
      throws IOException, InvalidTokenException, PermissionDeniedException {
    String playlistId = playlistData.getId();

    Optional<String> apiPaginationToken = pageInfo.isEmpty()
        ? Optional.empty()
        : pageInfo.get().getApiPagingToken();
    PlaylistItemListResponse playlistItemListResponse =
        getOrCreateMusicHttpApi(authData).listPlaylistItems(playlistId, apiPaginationToken);

    MusicContainerResource containerResource = null;
    GooglePlaylistItem[] googlePlaylistItems = playlistItemListResponse.getPlaylistItems();
    List<MusicPlaylistItem> playlistItems = new ArrayList<>();
    if (googlePlaylistItems != null && googlePlaylistItems.length > 0) {
      for (GooglePlaylistItem googlePlaylistItem : googlePlaylistItems) {
        playlistItems.add(convertPlaylistItem(playlistId, googlePlaylistItem));
      }
      containerResource = new MusicContainerResource(null, playlistItems, null, null);
    }

    // DO NOT MERGE - convert this to use TransferPageInfo and prefix this with the new TokenPrefix.PLAYLIST_ITEM correctly
    if (Strings.isNullOrEmpty(playlistItemListResponse.getNextPageToken())) {
      return new ExportResult<>(
          ResultType.CONTINUE,
          containerResource);
    } else {
      ContinuationData continuationData = continuationData(TokenPrefix.PLAYLIST_ITEM, playlistItemListResponse.getNextPageToken());
      return new ExportResult<>(
          ResultType.CONTINUE,
          containerResource,
          continuationData);
    }
  }

  /* DO NOT MERGE - move this helper elsewhere; perhaps wherever TokenPrefix ultimately lives? */
  public static ContinuationData continuationData(TokenPrefix prefix, String apiToken) {
    return new ContinuationData(new StringPaginationToken(prefix + apiToken));
  }

  private List<MusicGroup> createMusicGroups(String[] artistTitles) {
    if (artistTitles == null) {
      return null;
    }
    List<MusicGroup> musicGroups = new ArrayList<>();
    for (String artistTitle : artistTitles) {
      musicGroups.add(new MusicGroup(artistTitle));
    }
    return musicGroups;
  }

  private MusicPlaylistItem convertPlaylistItem(
      String playlistId, GooglePlaylistItem googlePlaylistItem) {
    GoogleTrack track = googlePlaylistItem.getTrack();
    GoogleRelease release = track.getRelease();
    return new MusicPlaylistItem(
        new MusicRecording(
            track.getIsrc(),
            track.getTrackTitle(),
            track.getDurationMillis(),
            new MusicRelease(
                release.getIcpn(),
                release.getReleaseTitle(),
                createMusicGroups(release.getArtistTitles())),
            createMusicGroups(track.getArtistTitles())),
        playlistId,
        googlePlaylistItem.getOrder());
  }

  private synchronized GoogleMusicHttpApi getOrCreateMusicHttpApi(TokensAndUrlAuthData authData) {
    if (musicHttpApi == null) {
      musicHttpApi = makeMusicHttpApi(authData);
    }
    return musicHttpApi;
  }

  private synchronized GoogleMusicHttpApi makeMusicHttpApi(TokensAndUrlAuthData authData) {
    return new GoogleMusicHttpApi(
        authData, jsonFactory, monitor, credentialFactory, /* arbitrary writesPerSecond */ 1.0);
  }
}
