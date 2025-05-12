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

package org.datatransferproject.datatransfer.sdata.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;

@AutoValue
@JsonDeserialize(builder = AutoValue_SdataMediaAlbumListResponse.Builder.class)
public abstract class SdataMediaAlbumListResponse implements Serializable {
  @JsonProperty("albums")
  public abstract ImmutableList<SdataMediaAlbum> albums();

  @JsonProperty("nextPageToken")
  public abstract String nextPageToken();

  public static Builder builder() {
    return new AutoValue_SdataMediaAlbumListResponse.Builder();
  }

  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder setAlbums(ImmutableList<SdataMediaAlbum> albums);

    public abstract Builder setNextPageToken(String nextPageToken);

    public abstract SdataMediaAlbumListResponse build();
  }
}
