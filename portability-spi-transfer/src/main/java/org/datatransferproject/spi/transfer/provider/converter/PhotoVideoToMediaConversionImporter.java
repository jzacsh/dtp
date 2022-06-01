package org.datatransferproject.spi.transfer.provider.converter;

import java.util.UUID;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.provider.ImportResult;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.types.common.models.DataModel;
import org.datatransferproject.types.common.models.media.MediaContainerResource;
import org.datatransferproject.types.common.models.photos.PhotosContainerResource;
import org.datatransferproject.types.common.models.videos.VideoContainerResource;
import org.datatransferproject.types.transfer.auth.AuthData;


// TODO(WIP) audit javadoc on _all_ the converter package to follow this javadoc as a template.
/**
 * Automatically produces a "MEDIA" importer from more basic "PHOTOS" and "VIDEO" importers.
 *
 * Produces an `Importer` that can handle import jobs for `MediaContainerResource` (aka "MEDIA"
 * jobs) given two existing importers: one that can handle the specific jobs of importing
 * `PhotosContainerResource` (aka "PHOTOS" jobs) and a second that can handle
 * `VideoContainerResource` `MediaContainerResource` (aka "VIDEO" jobs).
 *
 * This is intended for providers who do not support "MEDIA" as a special case.
 */
// TODO(WIP) fix the primitives-obession causing us to key Providers on "PHOTOS" string rather
// than underlying file types.
public class PhotoVideoToMediaConversionImporter<
    A extends AuthData,
    PCR extends PhotosContainerResource,
    VCR extends VideoContainerResource,
    WrappedPhotoImporter extends Importer<A, PCR>,
    WrappedVideoImporter extends Importer<A, VCR>,
    > implements Importer<A, MediaContainerResource> {
  private final WrappedPhotoImporter wrappedPhotoImporter;
  private final WrappedVideoImporter wrappedVideoImporter;

  public PhotoToMediaConverionImporter(
      WrappedPhotoImporter wrappedPhotoImporter,
      WrappedVideoImporter wrappedVideoImporter) {
    this.wrappedPhotoImporter = wrappedPhotoImporter;
    this.wrappedVideoImporter = wrappedVideoImporter;
  }

  ImportResult importItem(
      UUID jobId,
      IdempotentImportExecutor idempotentExecutor,
      A authData,
      MediaContainerResource data) throws Exception {
    VCR generatedVideoContainer; // TODO(DO NOT MERGE) complete this using the existing utils
    PCR generatedPhotoContainer; // TODO(DO NOT MERGE) complete this using the existing utils

    wrappedPhotoImporter.importItem(jobId, idempotentexecutor, authData, generatedVideoContainer);
    wrappedVideoImporter.importItem(jobId, idempotentexecutor, authData, generatedPhotoContainer);
  }
}
