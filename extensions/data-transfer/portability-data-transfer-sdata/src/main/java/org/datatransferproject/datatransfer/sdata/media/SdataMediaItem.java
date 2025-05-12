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
import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import java.io.Serializable;
import org.datatransferproject.types.common.DownloadableFile;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;

/** Media item returned by queries to the Google Photos API. Represents what is stored by Google. */
@AutoValue
@AutoOneOf(SdataMediaItem.SdataMediaItemType.class)
@JsonDeserialize(builder = AutoValue_SdataMediaItem.Builder.class)
public abstract class SdataMediaItem implements Serializable {

  public enum SdataMediaItemType {
    PHOTO,
    VIDEO
  }

  @JsonProperty("type")
  public abstract SdataMediaItemType type();

  @JsonProperty("photo")
  public abstract PhotoModel photo();

  @JsonProperty("video")
  public abstract VideoModel video();

  public DownloadableFile getFile() {
    switch (type()) {
      case PHOTO:
        return photo();
      case VIDEO:
        return video();
    }
    throw new AssertionError("exhaustive switch");
  }

  public static SdataMediaItem ofPhoto(PhotoModel photo) {
    return builder().setType(SdataMediaItemType.PHOTO).setPhoto(photo).build();
  }

  public static SdataMediaItem ofVideo(VideoModel video) {
    return builder().setType(SdataMediaItemType.VIDEO).setVideo(video).build();
  }

  public static Builder builder() {
    return new AutoValue_SdataMediaItem.Builder();
  }

  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder setPhoto(PhotoModel photo);

    public abstract Builder setType(SdataMediaItemType photo);

    public abstract Builder setVideo(VideoModel video);

    public abstract SdataMediaItem build();
  }
}
