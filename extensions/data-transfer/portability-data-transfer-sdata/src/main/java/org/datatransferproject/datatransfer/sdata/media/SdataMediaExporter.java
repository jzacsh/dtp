/*
 * Copyright 2025 The Data Transfer Project Authors.
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
package org.datatransferproject.datatransfer.sdata.media;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;
import static org.datatransferproject.datatransfer.sdata.common.SdataErrorLogger.logFailedItemErrors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.JsonFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.tuple.Pair;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.sdata.common.JobContext;
import org.datatransferproject.datatransfer.sdata.common.JobService;
import org.datatransferproject.datatransfer.sdata.common.SdataCredentialFactory;
import org.datatransferproject.datatransfer.sdata.remote.RemoteStaticService;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.provider.ExportResult.ResultType;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.spi.transfer.types.CopyExceptionWithFailureReason;
import org.datatransferproject.spi.transfer.types.TempMediaData;
import org.datatransferproject.types.common.ExportInformation;
import org.datatransferproject.types.common.PaginationData;
import org.datatransferproject.types.common.StringPaginationToken;
import org.datatransferproject.types.common.models.IdOnlyContainerResource;
import org.datatransferproject.types.common.models.media.MediaAlbum;
import org.datatransferproject.types.common.models.media.MediaContainerResource;
import org.datatransferproject.types.common.models.photos.PhotoAlbum;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.photos.PhotosContainerResource;
import org.datatransferproject.types.common.models.videos.VideoModel;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;
import org.datatransferproject.types.transfer.errors.ErrorDetail;

public class SdataMediaExporter implements Exporter<TokensAndUrlAuthData, MediaContainerResource> {
  /** Upper limit of reads per second for {@link RemoteStaticService}. */
  private static final double MAX_READS_PER_SECOND = 10;

  private static final String CACHE_KEY_TEMP_MEDIA_DATA = "tempMediaData";
  private static final String ALBUM_TOKEN_PREFIX = "album:";
  private static final String MEDIA_TOKEN_PREFIX = "media:";

  private final SdataCredentialFactory credentialFactory;
  private final JobStore jobStore;
  private final JsonFactory jsonFactory;
  private final Monitor monitor;

  public SdataMediaExporter(
      SdataCredentialFactory credentialFactory,
      JobStore jobStore,
      JsonFactory jsonFactory,
      Monitor monitor) {
    this.credentialFactory = credentialFactory;
    this.jobStore = jobStore;
    this.jsonFactory = jsonFactory;
    this.monitor = monitor;
  }

  @Override
  public ExportResult<MediaContainerResource> export(
      UUID jobId, TokensAndUrlAuthData authData, Optional<ExportInformation> maybeExportInformation)
      throws IOException, CopyExceptionWithFailureReason {
    JobService<RemoteStaticService> jobService = buildPerJobService(jobId, authData);
    // If ExportInformation is missing, then this is a request to export the entire user library.
    final boolean isExportingEntireUserLibrary = maybeExportInformation.isEmpty();
    if (isExportingEntireUserLibrary) {
      // Make list of photos contained in albums so they are not exported twice later on
      storeContainedMediaIndex(jobService);
      return exportAlbums(Optional.empty(), jobService);
    }
    ExportInformation exportInformation = maybeExportInformation.get();

    final boolean isContinuingEntireUserLibrary = exportInformation == null;
    if (!isContinuingEntireUserLibrary) {
      if (exportInformation.getContainerResource() instanceof PhotosContainerResource) {
        // If ExportInformation is a photos container, this is a request to only export the contents
        // in that container instead of the whole user library
        return exportPhotosContainer(
            (PhotosContainerResource) exportInformation.getContainerResource(), jobService);
      } else if (exportInformation.getContainerResource() instanceof MediaContainerResource) {
        // If ExportInformation is a media container, this is a request to only export the contents
        // in that container instead of the whole user library (this is to support backwards
        // compatibility with the GooglePhotosExporter)
        return exportMediaContainer(
            (MediaContainerResource) exportInformation.getContainerResource(), jobService);
      } else {
        throw new IllegalArgumentException(
            "unexpected ExportInformation container resource found: "
                + exportInformation.toString());
      }
    }

    /*
     * Use the export information to determine whether this export call should export albums or
     * photos.
     *
     * Albums are exported if and only if the export information doesn't hold an album
     * already, and the pagination token begins with the album prefix.  There must be a pagination
     * token for album export since this is isn't the first export operation performed (if it was,
     * there wouldn't be any export information at all).
     *
     * Otherwise, photos are exported. If photos are exported, there may or may not be pagination
     * information, and there may or may not be album information. If there is no container
     * resource, that means that we're exporting albumless photos and a pagination token must be
     * present. The beginning step of exporting albumless photos is indicated by a pagination token
     * containing only MEDIA_TOKEN_PREFIX with no token attached, in order to differentiate this
     * case for the first step of export (no export information at all).
     */
    Optional<StringPaginationToken> paginationToken = getPaginationToken(exportInformation);
    Optional<IdOnlyContainerResource> idOnlyContainerResource =
        ofNullable((IdOnlyContainerResource) exportInformation.getContainerResource());

    if (idOnlyContainerResource.isEmpty() && hasMoreAlbumListingPages(paginationToken)) {
      // were still listing out all of the albums since we have pagination data
      return exportAlbums(getAlbumNextPageToken(paginationToken), jobService);
    } else {
      return exportMedia(idOnlyContainerResource, paginationToken, jobService);
    }
  }

  /** Constructs a two-tuple of TransferExtension context and User-job context. */
  private JobService<RemoteStaticService> buildPerJobService(
      UUID jobId, TokensAndUrlAuthData authData)
      throws IOException, CopyExceptionWithFailureReason {
    JobContext context = JobContext.builder().setJobId(jobId).setAuthData(authData).build();
    Credential credential = credentialFactory.createCredential(context.authData());
    RemoteStaticService service =
        new RemoteStaticService(
            credentialFactory,
            credential,
            jsonFactory,
            monitor,
            MAX_READS_PER_SECOND,
            JobService.<JobStore>builder().setContext(context).setService(jobStore).build());
    return JobService.<RemoteStaticService>builder()
        .setContext(context)
        .setService(service)
        .build();
  }

  private static Optional<StringPaginationToken> getPaginationToken(
      ExportInformation exportInformation) {
    return ofNullable((StringPaginationToken) exportInformation.getPaginationData());
  }

  private static boolean isPrefixedToken(
      Optional<StringPaginationToken> paginationToken, String prefix) {
    return paginationToken
        .map(StringPaginationToken::getToken)
        .map(t -> t.startsWith(prefix))
        .orElse(false);
  }

  private static StringPaginationToken buildPrefixedToken(String prefix, String rawToken) {
    checkState(!isNullOrEmpty(rawToken), "page token required to build a %s-token", prefix);
    return new StringPaginationToken(prefix + rawToken);
  }

  private static StringPaginationToken buildAlbumNextPageToken(String rawNextPageToken) {
    return buildPrefixedToken(ALBUM_TOKEN_PREFIX, rawNextPageToken);
  }

  private static StringPaginationToken buildMediaNextPageToken(String rawNextPageToken) {
    return buildPrefixedToken(MEDIA_TOKEN_PREFIX, rawNextPageToken);
  }

  private static boolean hasMoreAlbumListingPages(Optional<StringPaginationToken> paginationToken) {
    return isPrefixedToken(paginationToken, ALBUM_TOKEN_PREFIX);
  }

  private static Optional<String> getEmbeddedToken(
      Optional<StringPaginationToken> paginationToken, String prefix) {
    return paginationToken
        .map(StringPaginationToken::getToken)
        .map(
            t -> {
              checkArgument(t.startsWith(prefix), "bad %s-pagination token \"%s\"", prefix, t);
              return t.substring(prefix.length());
            })
        .flatMap(embedded -> optionalOfString(embedded));
  }

  private static Optional<String> getAlbumNextPageToken(
      Optional<StringPaginationToken> paginationToken) {
    return getEmbeddedToken(paginationToken, ALBUM_TOKEN_PREFIX);
  }

  private static Optional<String> getPhotosPaginationToken(
      Optional<StringPaginationToken> paginationToken) {
    return getEmbeddedToken(paginationToken, MEDIA_TOKEN_PREFIX);
  }

  /* Maintain this for backwards compatibility, so that we can pull out the album information */
  private ExportResult<MediaContainerResource> exportPhotosContainer(
      PhotosContainerResource container, JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    ImmutableList.Builder<MediaAlbum> albumsBuilder = ImmutableList.builder();
    ImmutableList.Builder<PhotoModel> photosBuilder = ImmutableList.builder();
    List<IdOnlyContainerResource> subResources = new ArrayList<>();

    for (PhotoAlbum album : container.getAlbums()) {
      SdataMediaAlbum sdataMediaAlbum =
          getSdataMediaAlbum(album.getIdempotentId(), album.getId(), album.getName(), jobService);

      albumsBuilder.add(new MediaAlbum(sdataMediaAlbum.id(), sdataMediaAlbum.title(), null));
      // Adding subresources tells the framework to recall export to get all the photos
      subResources.add(new IdOnlyContainerResource(sdataMediaAlbum.id()));
    }

    ImmutableList.Builder<ErrorDetail> errors = ImmutableList.builder();
    for (PhotoModel photo : container.getPhotos()) {
      SdataMediaItem sdataMediaItem =
          getSdataMediaItem(
              photo.getIdempotentId(), photo.getDataId(), photo.getName(), jobService);
      photosBuilder.add(sdataMediaItem.photo());
    }
    // Log all the errors in 1 commit to DataStore
    logFailedItemErrors(jobStore, jobService.context().jobId(), errors.build());

    MediaContainerResource readyForExport =
        new MediaContainerResource(albumsBuilder.build(), photosBuilder.build(), null /* videos */);
    ContinuationData continuationData = new ContinuationData(null);
    subResources.forEach(resource -> continuationData.addContainerResource(resource));
    return new ExportResult<>(ResultType.CONTINUE, readyForExport, continuationData);
  }

  /* Maintain this for backwards compatibility, so that we can pull out the album information */
  private ExportResult<MediaContainerResource> exportMediaContainer(
      MediaContainerResource container, JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    Pair<ImmutableList<MediaAlbum>, ImmutableList<IdOnlyContainerResource>> albums =
        exportAlbumsToDtpModels(container, jobService);
    Pair<ImmutableList<PhotoModel>, ImmutableList<ErrorDetail>> photos =
        exportPhotosToDtpModels(container, jobService);
    Pair<ImmutableList<VideoModel>, ImmutableList<ErrorDetail>> videos =
        exportVideosToDtpModels(container, jobService);

    MediaContainerResource readyForExport =
        new MediaContainerResource(albums.getLeft(), photos.getLeft(), videos.getLeft());

    ContinuationData continuationData = new ContinuationData(null);
    albums.getRight().forEach(resource -> continuationData.addContainerResource(resource));

    // Log all the errors in 1 commit to DataStore
    logFailedItemErrors(
        jobStore,
        jobService.context().jobId(),
        ImmutableList.<ErrorDetail>builder()
            .addAll(photos.getRight())
            .addAll(videos.getRight())
            .build());

    return new ExportResult<>(ResultType.CONTINUE, readyForExport, continuationData);
  }

  private Pair<ImmutableList<MediaAlbum>, ImmutableList<IdOnlyContainerResource>>
      exportAlbumsToDtpModels(
          MediaContainerResource container, JobService<RemoteStaticService> jobService)
          throws IOException, CopyExceptionWithFailureReason {
    ImmutableList.Builder<MediaAlbum> albumsBuilder = ImmutableList.builder();
    ImmutableList.Builder<IdOnlyContainerResource> subResourcesBuilder = ImmutableList.builder();
    for (MediaAlbum album : container.getAlbums()) {
      SdataMediaAlbum sdataMediaAlbum =
          getSdataMediaAlbum(album.getIdempotentId(), album.getId(), album.getName(), jobService);
      albumsBuilder.add(
          new MediaAlbum(sdataMediaAlbum.id(), sdataMediaAlbum.title(), /* description= */ null));
      // Adding subresources tells the framework to recall export to get all the photos
      subResourcesBuilder.add(new IdOnlyContainerResource(sdataMediaAlbum.id()));
    }
    return Pair.of(albumsBuilder.build(), subResourcesBuilder.build());
  }

  private Pair<ImmutableList<PhotoModel>, ImmutableList<ErrorDetail>> exportPhotosToDtpModels(
      MediaContainerResource container, JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    ImmutableList.Builder<PhotoModel> photosBuilder = ImmutableList.builder();
    ImmutableList.Builder<ErrorDetail> errorsBuilder = ImmutableList.builder();
    for (PhotoModel photo : container.getPhotos()) {
      SdataMediaItem photoMediaItem =
          getSdataMediaItem(
              photo.getIdempotentId(), photo.getDataId(), photo.getName(), jobService);
      checkState(
          photoMediaItem.type() == SdataMediaItem.SdataMediaItemType.PHOTO,
          "Expected photo for ID %s, got %s",
          photo.getIdempotentId(),
          photoMediaItem.type());
      photosBuilder.add(photoMediaItem.photo());
    }
    return Pair.of(photosBuilder.build(), errorsBuilder.build());
  }

  private Pair<ImmutableList<VideoModel>, ImmutableList<ErrorDetail>> exportVideosToDtpModels(
      MediaContainerResource container, JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    ImmutableList.Builder<VideoModel> videosBuilder = ImmutableList.builder();
    ImmutableList.Builder<ErrorDetail> errorsBuilder = ImmutableList.builder();
    for (VideoModel video : container.getVideos()) {
      SdataMediaItem videoMediaItem =
          getSdataMediaItem(
              video.getIdempotentId(), video.getDataId(), video.getName(), jobService);
      checkState(
          videoMediaItem.type() == SdataMediaItem.SdataMediaItemType.VIDEO,
          "Expected video for ID %s, got %s",
          video.getIdempotentId(),
          videoMediaItem.type());
      videosBuilder.add(videoMediaItem.video());
    }
    return Pair.of(videosBuilder.build(), errorsBuilder.build());
  }

  /**
   * Note: not all accounts have albums to return. In that case, we just return an empty list of
   * albums instead of trying to iterate through a null list.
   */
  @VisibleForTesting
  ExportResult<MediaContainerResource> exportAlbums(
      Optional<String> nextPageToken, JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    ImmutableList.Builder<MediaAlbum> albums = ImmutableList.builder();
    List<IdOnlyContainerResource> subResources = new ArrayList<>();
    SdataMediaAlbumListResponse albumListResponse = listAlbums(nextPageToken, jobService);
    StringPaginationToken nextPageData =
        optionalOfString(albumListResponse.nextPageToken())
            .map(t -> buildAlbumNextPageToken(t))
            .orElse(new StringPaginationToken(MEDIA_TOKEN_PREFIX));

    List<SdataMediaAlbum> sdataMediaAlbums = albumListResponse.albums();
    for (SdataMediaAlbum sdataMediaAlbum : sdataMediaAlbums) {
      // Add album info to list so album can be recreated later
      MediaAlbum album = new MediaAlbum(sdataMediaAlbum.id(), sdataMediaAlbum.title(), null);
      albums.add(album);

      monitor.debug(
          () ->
              format(
                  "%s: SdataMedia exporting album: %s",
                  jobService.context().jobId(), album.getId()));

      // Add album id to continuation data
      subResources.add(new IdOnlyContainerResource(sdataMediaAlbum.id()));
    }
    ContinuationData continuationData = new ContinuationData(nextPageData);
    subResources.forEach(resource -> continuationData.addContainerResource(resource));
    MediaContainerResource readyFoExport = new MediaContainerResource(albums.build(), null, null);
    return new ExportResult<>(ResultType.CONTINUE, readyFoExport, continuationData);
  }

  @VisibleForTesting
  ExportResult<MediaContainerResource> exportMedia(
      Optional<IdOnlyContainerResource> albumData,
      Optional<StringPaginationToken> paginationData,
      JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    Optional<String> albumId = albumData.map(IdOnlyContainerResource::getId);
    Optional<String> paginationToken = getPhotosPaginationToken(paginationData);

    SdataMediaItemSearchResponse response = listMediaItems(albumId, paginationToken, jobService);

    Optional<PaginationData> nextPageData =
        optionalOfString(response.nextPageToken()).map(t -> buildMediaNextPageToken(t));

    ContinuationData continuationData = new ContinuationData(nextPageData.orElse(null));

    ImmutableList<SdataMediaItem> sdataItemsToExport =
        filterForUnexportedMedia(response.mediaItems(), albumId, jobService.context().jobId());

    MediaContainerResource readyForExport = convertToDtpContainer(sdataItemsToExport).orElse(null);

    ResultType resultType = nextPageData.map(d -> ResultType.CONTINUE).orElse(ResultType.END);
    return new ExportResult<>(resultType, readyForExport, continuationData);
  }

  /**
   * Computes and stores a list of any photos that are already contained in albums, across a user's
   * entire library.
   *
   * <p>Read from the resulting index later {@link #openContainedMediaIndex}.
   */
  private void storeContainedMediaIndex(JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    // This method is only called once at the beginning of the transfer, so we can start by
    // initializing a new TempMediaData to be store in the job store.
    TempMediaData tempMediaData = new TempMediaData(jobService.context().jobId());

    SdataMediaAlbumListResponse albumListResponse;
    Optional<String> nextAlbumListToken = Optional.empty();
    SdataMediaItemSearchResponse albumContents;
    do {
      albumListResponse = listAlbums(nextAlbumListToken, jobService);
      nextAlbumListToken = optionalOfString(albumListResponse.nextPageToken());
      for (SdataMediaAlbum album : albumListResponse.albums()) {
        checkState(
            !isNullOrEmpty(album.id()),
            "job %s: API bug: album missing an ID in an listAlbums() response",
            jobService.context().jobId().toString());
        Optional<String> nextSearchToken = Optional.empty();

        do {
          albumContents = listMediaItems(Optional.of(album.id()), nextSearchToken, jobService);
          nextSearchToken = optionalOfString(albumContents.nextPageToken());
          for (SdataMediaItem containedMediaItem : albumContents.mediaItems()) {
            tempMediaData.markContained(containedMediaItem.getFile());
          }
        } while (nextSearchToken.isPresent());
      }
    } while (nextAlbumListToken.isPresent());

    // TODO: if we see complaints about objects being too large for JobStore in other places, we
    // should consider putting logic in JobStore itself to handle it
    InputStream stream = convertJsonToInputStream(tempMediaData);
    jobStore.create(jobService.context().jobId(), CACHE_KEY_TEMP_MEDIA_DATA, stream);
  }

  /**
   * Might be empty in the event the current job isn't for an entire library (in which case we never
   * bothered creating an item-is-contained index).
   */
  private Optional<TempMediaData> openContainedMediaIndex(UUID jobId) throws IOException {
    return ofNullable(jobStore.getStream(jobId, CACHE_KEY_TEMP_MEDIA_DATA).getStream())
        .map(
            s -> {
              try (s) {
                return new ObjectMapper().readValue(s, TempMediaData.class);
              } catch (IOException e) {
                throw new IllegalStateException(
                    format("job: %s: reading TempMediaData from JobStore", jobId), e);
              }
            });
  }

  private ImmutableList<SdataMediaItem> filterForUnexportedMedia(
      ImmutableList<SdataMediaItem> mediaItems, Optional<String> albumId, UUID jobId)
      throws IOException {
    if (mediaItems.isEmpty()) {
      return mediaItems; // nothing to filter; don't bother talking to jobstore below
    }

    final Optional<TempMediaData> tempMediaData = openContainedMediaIndex(jobId);

    // Whether our callee is asking us to export content from the albumless-root of the user's
    // library.
    final boolean isAlbumlessRootExport = albumId.isEmpty();

    // Filter out item's we're confident are already exported as part of an album.
    ImmutableList<SdataMediaItem> itemsToExport = mediaItems;
    if (isAlbumlessRootExport && tempMediaData.isPresent()) {
      itemsToExport =
          mediaItems.stream()
              .filter(mediaItem -> tempMediaData.get().isContained(mediaItem.getFile()))
              .collect(toImmutableList());
    }
    return itemsToExport;
  }

  private Optional<MediaContainerResource> convertToDtpContainer(
      ImmutableList<SdataMediaItem> mediaItems) {
    if (mediaItems.isEmpty()) {
      return Optional.empty();
    }

    ImmutableList.Builder<PhotoModel> photos = ImmutableList.builder();
    ImmutableList.Builder<VideoModel> videos = ImmutableList.builder();
    for (SdataMediaItem mediaItem : mediaItems) {
      switch (mediaItem.type()) {
        case PHOTO:
          photos.add(mediaItem.photo());
          break;
        case VIDEO:
          videos.add(mediaItem.video());
          break;
        default:
          throw new IllegalStateException(
              format(
                  "Unexpected media type \"%s\" for ID %s: \"%s\"",
                  mediaItem.type(),
                  mediaItem.getFile().getIdempotentId(),
                  mediaItem.getFile().getName()));
      }

      monitor.debug(
          () ->
              format(
                  "SdataMedia exporting %s: %s",
                  mediaItem.type(), mediaItem.getFile().getIdempotentId()));
    }

    return Optional.of(
        new MediaContainerResource(/* albums= */ null, photos.build(), videos.build()));
  }

  // TODO: delete the custom exceptions (like FailedToListAlbumsException) in ../common/ as it seems
  // they're unused in the fork.
  SdataMediaAlbum getSdataMediaAlbum(
      String albumIdempotentId,
      String albumId,
      String albumName,
      JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    return jobService.service().getAlbum(albumId);
  }

  SdataMediaItem getSdataMediaItem(
      String photoIdempotentId,
      String photoDataId,
      String photoName,
      JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    return jobService.service().getMediaItem(photoDataId);
  }

  /**
   * Tries to call PhotosInterface.listAlbums, and retries on failure. If unsuccessful, throws a
   * FailedToListAlbumsException.
   */
  private SdataMediaAlbumListResponse listAlbums(
      Optional<String> pageToken, JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    return jobService.service().listAlbums(pageToken);
  }

  /**
   * Tries to call PhotosInterface.ListMediaItems, and retries on failure. If unsuccessful, throws a
   * FailedToListMediaItemsException.
   */
  private SdataMediaItemSearchResponse listMediaItems(
      Optional<String> albumId,
      Optional<String> pageToken,
      JobService<RemoteStaticService> jobService)
      throws IOException, CopyExceptionWithFailureReason {
    return jobService.service().listMediaItems(albumId, pageToken);
  }

  private static InputStream convertJsonToInputStream(Object jsonObject)
      throws JsonProcessingException {
    String tempString = new ObjectMapper().writeValueAsString(jsonObject);
    return new ByteArrayInputStream(tempString.getBytes(UTF_8));
  }

  /** String-friendly variant of {@link Optional#ofNullable}. */
  private static Optional<String> optionalOfString(String s) {
    return isNullOrEmpty(s) ? Optional.empty() : Optional.of(s);
  }
}
