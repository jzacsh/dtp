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

package org.datatransferproject.datatransfer.apple.photos;

import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.AuthorizeUploadResponse;
import org.datatransferproject.types.common.DownloadableFile;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;

/* Thin wrapper for {@link AuthorizeUploadResponse}. */
@AutoValue
public abstract class ApplePreUpload {
  public abstract URL uploadUrl();

  public abstract AuthorizeUploadResponse authorizeUploadResponse();

  static Builder builder() {
    return new AutoValue_ApplePreUpload.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setUploadUrl(URL uploadUrl);

    public abstract Builder setAuthorizeUploadResponse(
        AuthorizeUploadResponse authorizeUploadResponse);

    public abstract ApplePreUpload build();
  }

  public static ApplePreUpload of(AuthorizeUploadResponse authorizeUploadResponse)
      throws IllegalStateException {
    ApplePreUpload.Builder builder =
        ApplePreUpload.builder().setAuthorizeUploadResponse(authorizeUploadResponse);

    checkState(
        Strings.isNullOrEmpty(authorizeUploadResponse.getUploadUrl()),
        "bad Apple server response: got AuthorizeUploadResponse with URL value \"%s\"",
        authorizeUploadResponse.getUploadUrl());

    try {
      builder.setUploadUrl(new URL(authorizeUploadResponse.getUploadUrl()));
    } catch (MalformedURLException e) {
      throw new IllegalStateException(
          String.format(
              "bad Apple server response: got AuthorizeUploadResponse with malformed URL value"
                  + " \"%s\"",
              authorizeUploadResponse.getUploadUrl()),
          e);
    }

    return builder.build();
  }

  private static String getDescription(DownloadableFile downloadableFile) {
    if (downloadableFile instanceof PhotoModel) {
      return ((PhotoModel) downloadableFile).getDescription();
    }
    if (downloadableFile instanceof VideoModel) {
      return ((VideoModel) downloadableFile).getDescription();
    }
    return null;
  }

  private static Long getUploadedTime(DownloadableFile downloadableFile) {
    Date updatedTime = null;
    if (downloadableFile instanceof PhotoModel) {
      updatedTime = ((PhotoModel) downloadableFile).getUploadedTime();
    } else if (downloadableFile instanceof VideoModel) {
      updatedTime = ((VideoModel) downloadableFile).getUploadedTime();
    }
    return updatedTime == null ? null : updatedTime.getTime();
  }
}
