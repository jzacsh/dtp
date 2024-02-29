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

import com.google.auto.value.AutoValue;
import java.util.Date;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.NewMediaRequest;
import org.datatransferproject.types.common.DownloadableFile;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;

@AutoValue
public abstract class AppleNewUpload {
  /* ID within the Apple servers' namespace for the content being transferred. */
  public abstract DownloadableFile downloadableFile();

  /* ID within DTP's namespace for the content being transferred. */
  public abstract String originatingDtpDataId();

  /* ID within the Apple servers' namespace for the content being transferred. */
  public abstract String newlyStartedAppleDataId();

  static Builder builder() {
    return new AutoValue_AppleNewUpload.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setOriginatingDtpDataId(String originatingDtpDataId);

    public abstract Builder setNewlyStartedAppleDataId(String newlyStartedAppleDataId);

    public abstract Builder setDownloadableFile(DownloadableFile downloadableFile);

    public abstract AppleNewUpload build();
  }

  public NewMediaRequest toNewMediaRequest() {
    final DownloadableFile downloadableFile = this.downloadableFile();
    String filename = downloadableFile.getName();
    String description = getDescription(downloadableFile);
    String albumId = downloadableFile.getFolderId();
    String mediaType = downloadableFile.getMimeType();
    Long creationDateInMillis = getUploadedTime(downloadableFile);
    return AppleMediaInterface.createNewMediaRequest(
        this.originatingDtpDataId(),
        filename,
        description,
        albumId,
        mediaType,
        null /*encodingFormat*/,
        creationDateInMillis,
        this.newlyStartedAppleDataId());
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
