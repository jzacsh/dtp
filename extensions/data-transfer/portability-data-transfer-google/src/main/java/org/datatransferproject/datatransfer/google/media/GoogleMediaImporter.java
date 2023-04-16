/*
 * Copyright 2023 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.datatransferproject.datatransfer.google.media;

import static java.lang.String.format;
import static org.datatransferproject.datatransfer.google.photos.GooglePhotosInterface.ERROR_HASH_MISMATCH;
import static org.datatransferproject.datatransfer.google.videos.GoogleVideosInterface.uploadBatchOfVideos;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.JsonFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.rpc.Code;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.google.common.GoogleCredentialFactory;
import org.datatransferproject.datatransfer.google.common.GooglePhotosImportUtils;
import org.datatransferproject.datatransfer.google.common.gphotos.GPhotosUpload;
import org.datatransferproject.datatransfer.google.mediaModels.BatchMediaItemResponse;
import org.datatransferproject.datatransfer.google.mediaModels.GoogleAlbum;
import org.datatransferproject.datatransfer.google.mediaModels.NewMediaItemResult;
import org.datatransferproject.datatransfer.google.mediaModels.NewMediaItemUpload;
import org.datatransferproject.datatransfer.google.mediaModels.Status;
import org.datatransferproject.datatransfer.google.photos.GooglePhotosInterface;
import org.datatransferproject.datatransfer.google.photos.PhotoResult;
import org.datatransferproject.spi.cloud.connection.ConnectionProvider;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.cloud.storage.TemporaryPerJobDataStore;
import org.datatransferproject.spi.cloud.storage.TemporaryPerJobDataStore.InputStreamWrapper;
import org.datatransferproject.spi.cloud.types.PortabilityJob;
import org.datatransferproject.spi.transfer.i18n.BaseMultilingualDictionary;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.idempotentexecutor.ItemImportResult;
import org.datatransferproject.spi.transfer.provider.ImportResult;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.spi.transfer.types.DestinationMemoryFullException;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.spi.transfer.types.PermissionDeniedException;
import org.datatransferproject.spi.transfer.types.UploadErrorException;
import org.datatransferproject.types.common.DownloadableItem;
import org.datatransferproject.types.common.ImportableItem;
import org.datatransferproject.types.common.models.media.MediaContainerResource;
import org.datatransferproject.types.common.models.media.MediaAlbum;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

public class GoogleMediaImporter
    implements Importer<TokensAndUrlAuthData, MediaContainerResource> {

  private final GoogleCredentialFactory credentialFactory;
  private final JobStore jobStore;
  // TODO(aksingh737) why does one half of the Google photos interactions rely on DTP's
  // TemporaryPerJobDataStore and the other half on. JobStore - how do these relate? can they be
  // consilidated everywhere? at least in this class?
  private final TemporaryPerJobDataStore dataStore;
  private final JsonFactory jsonFactory;
  private final ConnectionProvider connectionProvider;
  private final Monitor monitor;
  private final double writesPerSecond;
  private final Map<UUID, GooglePhotosInterface> photosInterfacesMap;
  // TODO(aksingh737) delete the two interface-management approaches (map vs. singleton); the
  // singleton appears to have been left behind during PR #882
  private final GooglePhotosInterface photosInterface;
  private final HashMap<UUID, BaseMultilingualDictionary> multilingualStrings = new HashMap<>();
  private final PhotosLibraryClient photosLibraryClient;

  // We partition into groups of 49 as 50 is the maximum number of items that can be created
  // in one call. (We use 49 to avoid potential off by one errors)
  // https://developers.google.com/photos/library/guides/upload-media#creating-media-item
  private static final int BATCH_UPLOAD_SIZE = 49;

  public GoogleMediaImporter(
      GoogleCredentialFactory credentialFactory,
      JobStore jobStore,
      TemporaryPerJobDataStore dataStore,
      JsonFactory jsonFactory,
      Monitor monitor,
      double writesPerSecond) {
    this(
        credentialFactory,
        jobStore,
        dataStore,
        jsonFactory,
        new HashMap<>(),  /*photosInterfacesMap*/
        null,  /*photosInterface*/
        null,  /*photosLibraryClient*/
        new ConnectionProvider(jobStore),
        monitor,
        writesPerSecond);
  }

  @VisibleForTesting
  GoogleMediaImporter(
      GoogleCredentialFactory credentialFactory,
      JobStore jobStore,
      TemporaryPerJobDataStore dataStore,
      JsonFactory jsonFactory,
      Map<UUID, GooglePhotosInterface> photosInterfacesMap,
      GooglePhotosInterface photosInterface,
      PhotosLibraryClient photosLibraryClient,
      ConnectionProvider connectionProvider,
      Monitor monitor,
      double writesPerSecond) {
    this.credentialFactory = credentialFactory;
    this.jobStore = jobStore;
    this.dataStore = dataStore;
    this.jsonFactory = jsonFactory;
    this.photosInterfacesMap = photosInterfacesMap;
    this.photosInterface = photosInterface;
    this.photosLibraryClient = photosLibraryClient;
    this.connectionProvider = connectionProvider;
    this.monitor = monitor;
    this.writesPerSecond = writesPerSecond;
  }

  @Override
  public ImportResult importItem(
      UUID jobId,
      IdempotentImportExecutor idempotentImportExecutor,
      TokensAndUrlAuthData authData,
      MediaContainerResource data)
      throws Exception {
    if (data == null) {
      // Nothing to do
      return ImportResult.OK;
    }
    final GPhotosUpload gPhotosUpload = new GPhotosUpload(jobId, idempotentImportExecutor, authData);
    GooglePhotosInterface photosInterface = getOrCreatePhotosInterface(jobId, authData);

    // Uploads album metadata
    for (MediaAlbum album : data.getAlbums()) {
      idempotentImportExecutor.executeAndSwallowIOExceptions(
          album.getId(), album.getName(), () -> importSingleAlbum(jobId, authData, album));
    }

    long bytes =
        importPhotos(data.getPhotos(), gPhotosUpload)
        + importVideos(data.getVideos(), gPhotosUpload, photosInterface);

    final ImportResult result = ImportResult.OK;
    return result.copyWithBytes(bytes);
  }

  // TODO(aksingh737,jzacsh) fix unit tests across Google adapters to stop testing internal methods
  // like these, and just test importItem() (of
  // org.datatransferproject.spi.transfer.provider.Importer interface).
  @VisibleForTesting
  String importSingleAlbum(UUID jobId, TokensAndUrlAuthData authData, MediaAlbum inputAlbum)
      throws IOException, InvalidTokenException, PermissionDeniedException, UploadErrorException {
    // Set up album
    GoogleAlbum googleAlbum = new GoogleAlbum();
    googleAlbum.setTitle(GooglePhotosImportUtils.cleanAlbumTitle(inputAlbum.getName()));

    GoogleAlbum responseAlbum =
        getOrCreatePhotosInterface(jobId, authData).createAlbum(googleAlbum);
    return responseAlbum.getId();
  }

  long importPhotos(
      Collection<PhotoModel> photos,
      GPhotosUpload gPhotosUpload,
      GooglePhotosInterface photosInterface)
      throws Exception {
    return gPhotosUpload.uploadItemsViaBatching(
        photos,
        photosInterface::uploadBatchOfPhotos);
  }

  long importVideos(
      Collection<VideoModel> videos,
      GPhotosUpload gPhotosUpload)
      throws Exception {
    return gPhotosUpload.uploadItemsViaBatching(
        videos,
        this::importVideosBatch);
  }


  private long importVideosBatch(
      UUID jobId,
      TokensAndUrlAuthData authData,
      List<VideoModel> batch,
      IdempotentImportExecutor executor,
      String albumId)
      throws Exception {
    return uploadBatchOfVideos(
          jobId,
          batch,
          dataStore,
          photosLibraryClient,
          executor,
          connectionProvider,
          monitor);
  }

  private long processMediaResult(
      NewMediaItemResult mediaItem,
      ImportableItem item,
      IdempotentImportExecutor executor,
      long bytes)
      throws Exception {
    Status status = mediaItem.getStatus();
    if (status.getCode() == Code.OK_VALUE) {
      PhotoResult photoResult = new PhotoResult(mediaItem.getMediaItem().getId(), bytes);
      executor.importAndSwallowIOExceptions(
          item, itemToImport -> ItemImportResult.success(photoResult, bytes));
      return bytes;
    } else {
      executor.importAndSwallowIOExceptions(
          item,
          itemToImport ->
              ItemImportResult.error(
                  new IOException(
                      String.format(
                          "Media item could not be created. Code: %d Message: %s",
                          status.getCode(), status.getMessage())),
                  bytes));
      return 0;
    }
  }

  private synchronized GooglePhotosInterface getOrCreatePhotosInterface(
      UUID jobId, TokensAndUrlAuthData authData) {

    if (photosInterface != null) {
      return photosInterface;
    }

    if (photosInterfacesMap.containsKey(jobId)) {
      return photosInterfacesMap.get(jobId);
    }

    GooglePhotosInterface newInterface = makePhotosInterface(authData);
    photosInterfacesMap.put(jobId, newInterface);

    return newInterface;
  }

  private synchronized GooglePhotosInterface makePhotosInterface(TokensAndUrlAuthData authData) {
    Credential credential = credentialFactory.createCredential(authData);
    return new GooglePhotosInterface(
        credentialFactory, credential, jsonFactory, monitor, writesPerSecond);
  }

  private synchronized BaseMultilingualDictionary getOrCreateStringDictionary(UUID jobId) {
    if (!multilingualStrings.containsKey(jobId)) {
      PortabilityJob job = jobStore.findJob(jobId);
      String locale = job != null ? job.userLocale() : null;
      multilingualStrings.put(jobId, new BaseMultilingualDictionary(locale));
    }

    return multilingualStrings.get(jobId);
  }
}
