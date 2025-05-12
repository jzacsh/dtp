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
package org.datatransferproject.datatransfer.sdata.takeout;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.auto.value.AutoValue;
import java.io.Serializable;

/** Album's "supplemental" metadata - not the content of the album itself. */
@AutoValue
// TODO: sdata optimization: this should be moved to ../takeout/ package since
// it's GoogleTakeout-specific, and instead SdataMediaItem should embed the DTP
// model PhotoModel.
@JsonDeserialize(builder = AutoValue_TakeoutPhotosAlbumMetadata.Builder.class)
public abstract class TakeoutPhotosAlbumMetadata implements Serializable {

  // TODO: sdata optimization: remaining members should be JSON. However the
  // below JSON spec is wrong (it's a holdover from the gphotos public http
  // APIs; instead we simply need to transcribe the JSON found in foo.jpg's
  // sibling file: foo.jpg.supplemental.json). In the meantime however: this
  // being a JSON object we can construct is the correct approach.

  @JsonProperty("title")
  public abstract String title();

  @JsonProperty("description")
  public abstract String description();

  @JsonProperty("access")
  public abstract String access();

  public static Builder builder() {
    return new AutoValue_TakeoutPhotosAlbumMetadata.Builder();
  }

  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder setTitle(String value);

    public abstract Builder setDescription(String value);

    public abstract Builder setAccess(String value);

    public abstract TakeoutPhotosAlbumMetadata build();
  }
}
