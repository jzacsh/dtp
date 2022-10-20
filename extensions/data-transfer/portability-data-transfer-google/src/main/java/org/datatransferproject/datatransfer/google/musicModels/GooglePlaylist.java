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

package org.datatransferproject.datatransfer.google.musicModels;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import org.datatransferproject.types.common.models.music.MusicPlaylist;

/** Class representing a playlist as returned by the Google Music API. */
// TODO(jzacsh@) missing equals/hashcode noise; see if siyug would consider using autovalue?
public class GooglePlaylist {
  static final String GOOGLE_PLAYLIST_NAME_PREFIX = "playlists/";

  @JsonProperty("name")
  private String name;

  @JsonProperty("title")
  private String title;

  @JsonProperty("description")
  private String description;

  @JsonProperty("createTime")
  private long createTime;

  @JsonProperty("updateTime")
  private long updateTime;

  // TODO(critical WIP-feature step): Rename the playlist token after we have final decision
  @JsonProperty("token")
  private String token;

  public String getName() {
    return name;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public long getCreateTime() {
    return createTime;
  }

  public long getUpdateTime() {
    return updateTime;
  }

  public String getToken() {
    return token;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setCreateTime(long createTime) {
    this.createTime = createTime;
  }

  public void setUpdateTime(long updateTime) {
    this.updateTime = updateTime;
  }

  public void setToken(String token) {
    this.token = token;
  }

  /**
   * Converts a scalar array - as recieved from an API response body for example - into a DTP-common
   * model of comparable usage.
   */
  public static ImmutableSet<MusicPlaylist> toMusicPlaylists(GooglePlaylist[] googleMusicPlaylists) {
    if (googleMusicPlaylists == null || googleMusicPlaylists.length <= 0) {
      return ImmutableSet.of();
    }

    Stream<GooglePlaylist> googPlaylistStream = Arrays.stream(googleMusicPlaylists);
    Set<MusicPlaylist> playlists = googPlaylistStream.map(GooglePlaylist::toMusicPlaylist).collect(Collectors.toSet());
    return ImmutableSet.copyOf(playlists);
  }

  public MusicPlaylist toMusicPlaylist() {
    return new MusicPlaylist(
        this.getName().substring(GooglePlaylist.GOOGLE_PLAYLIST_NAME_PREFIX.length()),
        this.getTitle(),
        this.getDescription(),
        Instant.ofEpochMilli(this.getCreateTime()),
        Instant.ofEpochMilli(this.getUpdateTime()));
  }
}
