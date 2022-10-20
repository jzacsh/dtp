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

import static org.datatransferproject.datatransfer.google.music.GoogleMusicExporter.GOOGLE_PLAYLIST_NAME_PREFIX;
import static org.datatransferproject.datatransfer.google.music.GoogleMusicExporter.TokenPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.api.client.json.gson.GsonFactory;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.google.common.GoogleCredentialFactory;
import org.datatransferproject.datatransfer.google.musicModels.GooglePlaylist;
import org.datatransferproject.datatransfer.google.musicModels.GooglePlaylistItem;
import org.datatransferproject.datatransfer.google.musicModels.GoogleRelease;
import org.datatransferproject.datatransfer.google.musicModels.GoogleTrack;
import org.datatransferproject.datatransfer.google.musicModels.PlaylistItemListResponse;
import org.datatransferproject.datatransfer.google.musicModels.PlaylistListResponse;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.spi.transfer.types.PermissionDeniedException;
import org.datatransferproject.types.common.ExportInformation;
import org.datatransferproject.types.common.PaginationData;
import org.datatransferproject.types.common.StringPaginationToken;
import org.datatransferproject.types.common.models.ContainerResource;
import org.datatransferproject.types.common.models.IdOnlyContainerResource;
import org.datatransferproject.types.common.models.music.MusicContainerResource;
import org.datatransferproject.types.common.models.music.MusicPlaylist;
import org.datatransferproject.types.common.models.music.MusicPlaylistItem;
import org.datatransferproject.types.common.models.music.MusicRecording;
import org.datatransferproject.types.common.models.music.MusicRelease;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GoogleMusicExporterTest {
  static final String PLAYLIST_PAGE_TOKEN = "some-playlist_page_token-494bd073a0dbfcdbba93be591ff9514e";
  static final String PLAYLIST_ITEM_TOKEN = "some-playlist_item_token-fbe14f4dcdf35ef860af38b860ae725b";

  private final TokensAndUrlAuthData fakeAuthData = null;
  private final UUID fakeJobId = UUID.randomUUID();

  private GoogleMusicExporter googleMusicExporter;
  private GoogleMusicHttpApi musicHttpApi;

  private PlaylistListResponse playlistListResponse;
  private PlaylistItemListResponse playlistItemListResponse;

  @BeforeEach
  public void setUp() throws IOException, InvalidTokenException, PermissionDeniedException {
    GoogleCredentialFactory credentialFactory = mock(GoogleCredentialFactory.class);
    musicHttpApi = mock(GoogleMusicHttpApi.class);

    Monitor monitor = mock(Monitor.class);

    googleMusicExporter =
        new GoogleMusicExporter(
            credentialFactory, GsonFactory.getDefaultInstance(), musicHttpApi, monitor);

    playlistListResponse = mock(PlaylistListResponse.class);
    playlistItemListResponse = mock(PlaylistItemListResponse.class);

    when(musicHttpApi.listPlaylists(any(Optional.class))).thenReturn(playlistListResponse);
    when(musicHttpApi.listPlaylistItems(any(String.class), any(Optional.class)))
        .thenReturn(playlistItemListResponse);

    verifyNoInteractions(credentialFactory);
  }

  @Test
  public void exportPlaylistFirstSet()
      throws IOException, InvalidTokenException, PermissionDeniedException {
    // Arrange
    setUpSinglePlaylist(GOOGLE_PLAYLIST_NAME_PREFIX + "p1_id");
    when(playlistListResponse.getNextPageToken()).thenReturn(PLAYLIST_PAGE_TOKEN);
    StringPaginationToken inputPaginationToken = new StringPaginationToken(TokenPrefix.PLAYLIST.toString());

    // Act
    ExportResult<MusicContainerResource> result = googleMusicExporter.export(
        fakeJobId,
        fakeAuthData,
        Optional.of(new ExportInformation(inputPaginationToken, /* containerResource= */ null)));

    // Assert: Check results
    // Verify correct methods were called
    verify(musicHttpApi).listPlaylists(Optional.empty());
    verify(playlistListResponse).getPlaylists();

    // Check pagination token
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertEquals(TokenPrefix.PLAYLIST + PLAYLIST_PAGE_TOKEN, paginationToken.getToken());

    // Check playlists field of container
    Collection<MusicPlaylist> actualPlaylists = result.getExportedData().getPlaylists();
    assertThat(actualPlaylists.stream().map(MusicPlaylist::getId).collect(Collectors.toList()))
        .containsExactly("p1_id");

    // Check playlistItems field of container (should be empty)
    List<MusicPlaylistItem> actualPlaylistItems = result.getExportedData().getPlaylistItems();
    assertThat(actualPlaylistItems).isEmpty();
    // Should be one container in the resource list
    List<ContainerResource> actualResources = continuationData.getContainerResources();
    assertThat(
            actualResources.stream()
                .map(a -> ((IdOnlyContainerResource) a).getId())
                .collect(Collectors.toList()))
        .containsExactly("p1_id");
  }

  @Test
  public void exportPlaylistSubsequentSet()
      throws IOException, InvalidTokenException, PermissionDeniedException {
    // Arrange
    setUpSinglePlaylist(GOOGLE_PLAYLIST_NAME_PREFIX + "p1_id");
    when(playlistListResponse.getNextPageToken()).thenReturn(null);
    StringPaginationToken inputPaginationToken =
        new StringPaginationToken(TokenPrefix.PLAYLIST + PLAYLIST_PAGE_TOKEN);

    // Act
    ExportResult<MusicContainerResource> result = googleMusicExporter.export(
        fakeJobId,
        fakeAuthData,
        Optional.of(new ExportInformation(inputPaginationToken, /* containerResource= */ null)));

    // Asert: Check results
    // Verify correct methods were called
    verify(musicHttpApi).listPlaylists(Optional.of(PLAYLIST_PAGE_TOKEN));
    verify(playlistListResponse).getPlaylists();

    // Check pagination token - should be absent
    ContinuationData continuationData = result.getContinuationData();
    assertThat(continuationData.getPaginationData()).isNull();
  }

  @Test
  public void exportPlaylistItemFirstSet()
      throws IOException, InvalidTokenException, PermissionDeniedException {
    // Arrange
    final String fakeNextPageToken = "yayyy-playlist_item_token-7f4dcae25bfbedf35ef860af38b860";
    GooglePlaylistItem playlistItem = setUpSinglePlaylistItem("t1_isrc", "r1_icpn");
    when(playlistItemListResponse.getPlaylistItems())
        .thenReturn(new GooglePlaylistItem[] {playlistItem});
    when(playlistItemListResponse.getNextPageToken()).thenReturn(fakeNextPageToken);

    // DO NOT MERGE  this is wrong, no? Shouldn't this be "playlist:" for the very first call into
    // the adapter?
    //PaginationData initialExportInfoPaginationData = null;
    // DO NOT MERGE; confirm with siyu that the below is more accurate
    PaginationData initialExportInfoPaginationData = new StringPaginationToken(TokenPrefix.PLAYLIST_ITEM.toString());

    // Act
    ExportResult<MusicContainerResource> result = googleMusicExporter.export(
        fakeJobId,
        fakeAuthData,
        Optional.of(new ExportInformation(
            initialExportInfoPaginationData,
            new IdOnlyContainerResource("p1_id"))));

    // Assert: Check pagination
    ContinuationData continuationData = result.getContinuationData();
    StringPaginationToken paginationToken =
        (StringPaginationToken) continuationData.getPaginationData();
    assertThat(paginationToken.getToken()).isEqualTo(TokenPrefix.PLAYLIST_ITEM + fakeNextPageToken);

    // Assert: Check results
    // Verify correct methods were called
    verify(musicHttpApi).listPlaylistItems("p1_id", Optional.empty());
    verify(playlistItemListResponse).getPlaylistItems();

    // Check playlist field of container (should be empty)
    Collection<MusicPlaylist> actualPlaylists = result.getExportedData().getPlaylists();
    assertThat(actualPlaylists).isEmpty();

    // Check playlistItems field of container
    List<MusicPlaylistItem> actualPlaylistItems = result.getExportedData().getPlaylistItems();
    assertThat(
            actualPlaylistItems.stream()
                .map(MusicPlaylistItem::getPlaylistId)
                .collect(Collectors.toList()))
        .containsExactly("p1_id"); // for download
    assertThat(
            actualPlaylistItems.stream()
                .map(MusicPlaylistItem::getTrack)
                .collect(Collectors.toList()))
        .containsExactly(
            new MusicRecording("t1_isrc", null, 0L, new MusicRelease("r1_icpn", null, null), null));
  }

  @Test
  public void exportPlaylistItemSubsequentSet()
      throws IOException, InvalidTokenException, PermissionDeniedException {
    // Arrange
    GooglePlaylistItem playlistItem = setUpSinglePlaylistItem("t1_isrc", "r1_icpn");
    when(playlistItemListResponse.getPlaylistItems())
        .thenReturn(new GooglePlaylistItem[] {playlistItem});
    when(playlistItemListResponse.getNextPageToken()).thenReturn(null);


    // Act: Run test
    // Act
    ExportResult<MusicContainerResource> result = googleMusicExporter.export(
        fakeJobId,
        fakeAuthData,
        Optional.of(new ExportInformation(
            new StringPaginationToken(TokenPrefix.PLAYLIST_ITEM + PLAYLIST_ITEM_TOKEN),
            new IdOnlyContainerResource("p1_id"))));

    // Check results
    // Verify correct methods were called
    verify(musicHttpApi).listPlaylistItems("p1_id", Optional.of(PLAYLIST_ITEM_TOKEN));
    verify(playlistItemListResponse).getPlaylistItems();

    // Check pagination token wasn't produced
    assertThat(result.getContinuationData()).isNull();
  }

  /** Sets up a response with a single playlist, containing a single playlist item */
  private void setUpSinglePlaylist(String playlistName) {
    GooglePlaylist playlistEntry = new GooglePlaylist();
    playlistEntry.setName(playlistName);
    playlistEntry.setTitle("p1_title");
    playlistEntry.setDescription("p1_description");

    when(playlistListResponse.getPlaylists()).thenReturn(new GooglePlaylist[] {playlistEntry});
  }

  /** Sets up a response for a single playlist item */
  private GooglePlaylistItem setUpSinglePlaylistItem(String isrc, String icpn) {
    GooglePlaylistItem playlistItemEntry = new GooglePlaylistItem();
    GoogleTrack track = new GoogleTrack();
    GoogleRelease release = new GoogleRelease();
    release.setIcpn(icpn);
    track.setIsrc(isrc);
    track.setRelease(release);
    playlistItemEntry.setTrack(track);
    return playlistItemEntry;
  }
}
