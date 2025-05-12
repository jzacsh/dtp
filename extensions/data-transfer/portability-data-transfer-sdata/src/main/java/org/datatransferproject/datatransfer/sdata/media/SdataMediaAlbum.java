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
import java.io.Serializable;

/** Class representing an album as returned by the Google Photos API. */
@AutoValue
@JsonDeserialize(builder = AutoValue_SdataMediaAlbum.Builder.class)
public abstract class SdataMediaAlbum implements Serializable {

  @JsonProperty("id")
  public abstract String id();

  @JsonProperty("title")
  public abstract String title();

  public static Builder builder() {
    return new AutoValue_SdataMediaAlbum.Builder();
  }

  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder setId(String id);

    public abstract Builder setTitle(String title);

    public abstract SdataMediaAlbum build();
  }
}
