package org.datatransferproject.spi.transfer.provider.converter;

import java.util.UUID;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.provider.ImportResult;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.types.common.models.DataModel;
import org.datatransferproject.types.common.models.media.MediaContainerResource;
import org.datatransferproject.types.common.models.photos.PhotosContainerResource;
import org.datatransferproject.types.transfer.auth.AuthData;

public class PhotoVideoToMediaConversionImporter<
    A extends AuthData, PCR extends PhotosContainerResource, VCR extends VideoContainerResource, PI extends Importer<A, PCR>, VI extends Importer<A, VCR>, >
    implements Importer<A, MediaContainerResource> {
  private final PI wrappedPhotoImporter;
  private final VI wrappedVideoImporter;

  public PhotoToMediaConverionImporter(PI wrappedPhotoImporter, VI wrappedVideoImporter) {
    this.wrappedPhotoImporter = wrappedPhotoImporter;
    this.wrappedVideoImporter = wrappedVideoImporter;
  }

  ImportResult importItem(UUID jobId, IdempotentImportExecutor idempotentExecutor, A authData,
      MediaContainerResource data) throws Exception {
    VideoContainerResource
        generatedVideoContainer; // TODO(DO NOT MERGE) complete this using the existing utils
    PhotosContainerResource
        generatedPhotoContainer; // TODO(DO NOT MERGE) complete this using the existing utils

    wrappedVideoImporter.importItem(jobId, idempotentexecutor, authData, generatedVideoContainer);
    wrappedVideoImporter.importItem(jobId, idempotentexecutor, authData, generatedPhotoContainer);
  }
}
