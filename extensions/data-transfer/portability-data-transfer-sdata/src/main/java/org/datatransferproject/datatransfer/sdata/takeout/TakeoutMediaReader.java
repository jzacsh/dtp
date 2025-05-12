package org.datatransferproject.datatransfer.sdata.takeout;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.lang3.tuple.Pair;
import org.datatransferproject.datatransfer.sdata.common.JobService;
import org.datatransferproject.datatransfer.sdata.common.ZipEntryIterator;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaAlbum;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaAlbumListResponse;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaItem;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaItemSearchResponse;
import org.datatransferproject.datatransfer.sdata.remote.SdataReader;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.transfer.types.CopyExceptionWithFailureReason;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;

/**
 * Reads Google Photos--via Media DTP vertical--user data from a Takeout archive (typically inflated
 * from a tarball or zip) which they themselves (a developer) locally installed.
 *
 * <p>WARNING: this class is not thread-safe, as it will contain state pertaining to the zip it
 * inspects, so you must construct a new one per user-transfer job.
 */
public class TakeoutMediaReader
    implements SdataReader<
        SdataMediaAlbum,
        SdataMediaAlbumListResponse,
        SdataMediaItem,
        SdataMediaItemSearchResponse> {
  private static final int DEFAULT_PAGE_SIZE = 500;
  private static final String TAKEOUT_ZIP_GOOGLE_PHOTOS_ROOT = "Takeout/Google Photos/";
  // Cache key prefix we use to store each zip entry in tempstore.
  private static final String ZIP_ENTRY_DUPLICATE_KEY = "takeout_zip_entry_duplicate";

  // https://developers.google.com/photos/library/guides/upload-media#file-types-sizes
  private static final Set<String> IMAGE_EXTENSIONS =
      new HashSet<>(
          Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp", "heic", "heif"));

  private final String takeoutZipBaseName;
  private final JobService<JobStore> perJobStore;
  private final int pageSize;

  // Cache of albumIds already seen, while a nextPageToken has been handed out.
  // TODO: sdata optimization: probably better to maintain this in a backing JobStore instead.
  private Set<String> seenAlbumIds;

  // Map from albumIds to cache of itemIds already seen for that album, while a nextPageToken has
  // been handed out.
  // TODO: sdata optimization: probably better to maintain this in a backing JobStore instead.
  private Map<String, Set<String>> albumsToSeenItemIds;

  public TakeoutMediaReader(String baseName, JobService<JobStore> perJobStore, int pageSize)
      throws IOException, CopyExceptionWithFailureReason {
    this.pageSize = pageSize;
    this.perJobStore = perJobStore;
    this.takeoutZipBaseName = baseName;

    this.seenAlbumIds = new HashSet<>(this.pageSize);
    this.albumsToSeenItemIds = new HashMap<>(this.pageSize);
    checkArgument(pageSize > 0, "pageSize must be > 0");
    assertSdataExists(this.takeoutZipBaseName);
  }

  public TakeoutMediaReader(String baseName, JobService<JobStore> perJobStore)
      throws IOException, CopyExceptionWithFailureReason {
    this(baseName, perJobStore, DEFAULT_PAGE_SIZE);
  }

  private InputStream streamSdata() throws IOException, CopyExceptionWithFailureReason {
    return SdataReader.streamSdata(takeoutZipBaseName);
  }

  @Override
  public void assertSdataExists(String sdataSubPath)
      throws IOException, CopyExceptionWithFailureReason {
    SdataReader.assertSdataNonEmpty(sdataSubPath);
    try (InputStream stream = SdataReader.streamSdata(sdataSubPath)) {
      boolean isNonEmptyArchive =
          ZipEntryIterator.from(stream)
                  .filter(entry -> entry.getName().startsWith(TAKEOUT_ZIP_GOOGLE_PHOTOS_ROOT))
                  .limit(1)
                  .count()
              == 1;
      checkState(
          isNonEmptyArchive,
          "expected at least one entry under %s in zip file: %s",
          TAKEOUT_ZIP_GOOGLE_PHOTOS_ROOT,
          sdataSubPath);
    } catch (IOException e) {
      throw new IllegalStateException(
          format(
              "bad sdata stream? hit IOException simply closing stream for zip: %s",
              takeoutZipBaseName),
          e);
    }
  }

  /** Lists all directories that are immediate children of the Google Photos root. */
  @Override
  public SdataMediaAlbumListResponse listContainers(Optional<String> nextPageToken)
      throws IOException, CopyExceptionWithFailureReason {
    SdataMediaAlbumListResponse.Builder response = SdataMediaAlbumListResponse.builder();
    ImmutableList.Builder<SdataMediaAlbum> albums = ImmutableList.builder();

    /** Whether we're currently seeking the entry that matches nextPageToken. */
    boolean waitingForPageCursor = nextPageToken.isPresent();
    if (!waitingForPageCursor) {
      seenAlbumIds = new HashSet<>(pageSize); // reset, since we're starting a new listing
    }

    try (ZipInputStream stream = getZipInputStream()) {
      while (true) {
        Optional<ZipEntry> entry = getNextZipEntry(stream);
        if (entry.isEmpty()) {
          break; // stop; at end of zip
        }
        final ZipEntry zipEntry = entry.get();
        final String zipEntryId = buildDataId(zipEntry);
        if (!zipEntry.getName().startsWith(TAKEOUT_ZIP_GOOGLE_PHOTOS_ROOT)) {
          continue; // skip; at Takeout content that's unrelated to our task
        }

        if (waitingForPageCursor) {
          if (nextPageToken.get().equals(zipEntryId)) {
            waitingForPageCursor = false; // we can stop seeking cursor and start processing
          } else {
            continue; // skip; not at the page cursor
          }
        }

        if (seenAlbumIds.contains(zipEntryId)) {
          // skip; we've already seen this during this call, or a previous call that the current
          // nextPageToken represents.
          continue;
        }

        // eg: "Besties/Hippo the Dog.jpg"
        final String itemPath = zipEntryId.substring(TAKEOUT_ZIP_GOOGLE_PHOTOS_ROOT.length());
        if (itemPath.isEmpty()) {
          continue; // skip; at the root dir itself
        }

        // eg: "Besties"
        final Optional<String> albumName = scrapeAlbumFromLeafFile(itemPath);
        if (albumName.isEmpty()) {
          continue; // skip; at a leaf file, but we're seeking directories
        }

        SdataMediaAlbum album =
            SdataMediaAlbum.builder().setId(zipEntryId).setTitle(albumName.get()).build();
        seenAlbumIds.add(zipEntryId);
        albums.add(album);

        // Note: we discard the build; apparently this is the norm:
        // https://github.com/google/guava/issues/2931
        if (albums.build().size() >= pageSize) {
          // Produce a next-page token, if we think there were more entries in the stream.
          Optional<ZipEntry> nextEntry = getNextZipEntry(stream);
          if (nextEntry.isPresent()) {
            response.setNextPageToken(buildDataId(nextEntry.get()));
          }
          break;
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          format(
              "bad sdata stream? hit IOException simply closing stream for zip: %s",
              takeoutZipBaseName),
          e);
    }

    final ImmutableList<SdataMediaAlbum> albumList = albums.build();
    checkState(
        !albumList.isEmpty() || nextPageToken.isPresent(),
        "bad sdata? zip appears to have no albums");
    return response.setAlbums(albumList).build();
  }

  /**
   * Google Photos only exports one directory depth: some metadata files atop its export, and some
   * immediate-children albums as folders. So we only have to deal with directory paths like:
   * "foo/bar.jpg" and not "foo/bar/baz.jpg".
   */
  private static Optional<String> scrapeAlbumFromLeafFile(String itemPath) {
    checkState(!isNullOrEmpty(itemPath), "found bad itemPath for ZipEntry");
    final int firstSlashIndex = itemPath.indexOf('/');
    // slash can be absent if we're interacting with a root-level metadata file
    // (eg: "metadata.json").
    return firstSlashIndex == -1
        ? Optional.empty()
        : Optional.of(itemPath.substring(0, firstSlashIndex));
  }

  @Override
  public SdataMediaAlbum getContainer(String containerId)
      throws IOException, CopyExceptionWithFailureReason {
    // TODO: sdata implementation: complete (_should_ be empty; but walk the tree anyway).
    // See listContainers() and listAlbumItems() implementations for inspiration.
    throw new UnsupportedOperationException("Not implemented yet: sdata implementation");
  }

  @Override
  public SdataMediaItem getItem(String itemId) throws IOException, CopyExceptionWithFailureReason {
    // TODO: sdata implementation: complete (_should_ be empty; but walk the tree anyway).
    // See listContainers() and listAlbumItems() implementations for inspiration.
    throw new UnsupportedOperationException("Not implemented yet: sdata implementation");
  }

  @Override
  public SdataMediaItemSearchResponse listItems(
      Optional<String> containerId, Optional<String> nextPageToken)
      throws IOException, CopyExceptionWithFailureReason {
    return containerId.isEmpty()
        ? listAlbumlessItems(nextPageToken)
        : listAlbumItems(containerId.get(), nextPageToken);
  }

  private SdataMediaItemSearchResponse listAlbumItems(
      String albumId, Optional<String> nextPageToken)
      throws IOException, CopyExceptionWithFailureReason {
    SdataMediaItemSearchResponse.Builder response = SdataMediaItemSearchResponse.builder();
    ImmutableList.Builder<SdataMediaItem> mediaItems = ImmutableList.builder();

    /** Whether we're currently seeking the entry that matches nextPageToken. */
    boolean waitingForPageCursor = nextPageToken.isPresent();
    if (!waitingForPageCursor || !albumsToSeenItemIds.containsKey(albumId)) {
      albumsToSeenItemIds.put(
          albumId, new HashSet<>(pageSize)); // reset, since we're starting a new listing
    }
    Set<String> seenItemIds =
        checkNotNull(
            albumsToSeenItemIds.get(albumId),
            "bug: albumId %s not in albumsToSeenItemIds, despite just having added it",
            albumId);

    try (ZipInputStream stream = getZipInputStream()) {
      while (true) {
        Optional<ZipEntry> entry = getNextZipEntry(stream);
        if (entry.isEmpty()) {
          break; // stop; at end of zip
        }
        final ZipEntry zipEntry = entry.get();
        final String zipEntryId = buildDataId(zipEntry);
        final String albumPathPrefix = TAKEOUT_ZIP_GOOGLE_PHOTOS_ROOT + "/" + albumId + "/";
        if (!zipEntry.getName().startsWith(albumPathPrefix)) {
          continue; // skip; at Takeout content that's unrelated to our task
        }

        if (waitingForPageCursor) {
          if (nextPageToken.get().equals(zipEntryId)) {
            waitingForPageCursor = false; // we can stop seeking cursor and start processing
          } else {
            continue; // skip; not at the page cursor
          }
        }

        if (seenItemIds.contains(zipEntryId)) {
          // skip; we've already seen this during this call, or a previous call that the current
          // nextPageToken represents.
          continue;
        }

        // eg: "Hippo the Dog.jpg"
        final String itemPath = zipEntry.getName().substring(albumPathPrefix.length());
        if (itemPath.isEmpty()) {
          continue; // skip; at the album's own root dir itself
        }

        if (itemPath.endsWith("/")
            || itemPath.endsWith(".json")
            || !mightHaveFileExtension(itemPath)) {
          continue; // skip; at a non-media item
        }

        seenItemIds.add(zipEntryId);
        mediaItems.add(loadMediaItemFromZipEntry(stream, zipEntry, albumPathPrefix, itemPath));

        // Note: we discard the build; apparently this is the norm:
        // https://github.com/google/guava/issues/2931
        if (mediaItems.build().size() >= pageSize) {
          // Produce a next-page token, if we think there were more entries in the stream.
          Optional<ZipEntry> nextEntry = getNextZipEntry(stream);
          if (nextEntry.isPresent()) {
            response.setNextPageToken(buildDataId(nextEntry.get()));
          }
          break;
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          format(
              "bad sdata stream? hit IOException simply closing stream for zip: %s",
              takeoutZipBaseName),
          e);
    }

    return response.setMediaItems(mediaItems.build()).build();
  }

  // TODO: sdata optimization: .json files can be extracted (of _similar_, but
  // not reliably identical, names to the  media item in question). Figure out
  // how to do this in a stable manner, and then we can fill in a LOT more
  // metadata using that JSON file before we return models back from this
  // method. See related TODO in TakeoutPhotosAlbumMetadata.java JSON pojo.
  private SdataMediaItem loadMediaItemFromZipEntry(
      ZipInputStream zipStream, ZipEntry zipEntry, String albumName, String itemPathName)
      throws IOException {
    final Optional<Pair<String, String>> fileExtSplit = splitOnFileExtension(itemPathName);
    checkState(
        fileExtSplit.isPresent(),
        "trying to scrape media item from zip entry without an extension: %s",
        zipEntry.getName());
    final String fileBaseName = fileExtSplit.get().getLeft();
    final String fileExtension = fileExtSplit.get().getRight();

    final String albumId = albumName;
    final String dataId = buildDataId(zipEntry);

    // TODO: sdata optimization: we unfortunately replicate the zip entry into our tempstore because
    // unfortunately DTP has historically hardcoded calls to `getFetchableUrl()` in importers so
    // that they each manually construct their own GET requests by hand (rather than the exporter
    // that _provided_ said URL being the expert on how to GET it). So the optimization-TODO here is
    // to add a new interface method to all the models, via something like
    // DownloadableFile#getInputStream(), and migrate old patterns, and delete this
    // storeMediaBytesInTempStore() in favor of just implementing a handler for the new
    // getInputStream() (that would just stream directly out of the zip).
    storeMediaBytesInTempStore(zipStream, zipEntry);

    if (isApparentlyImage(fileExtension)) {
      return SdataMediaItem.ofPhoto(
          new PhotoModel(
              fileBaseName,
              null /*fetchableUrl*/,
              null /*description*/,
              null /*mediaType*/,
              dataId,
              albumId,
              true /*inTempStore*/));
    } else {
      return SdataMediaItem.ofVideo(
          new VideoModel(
              fileBaseName,
              null /*contentUrl*/,
              null /*description*/,
              null /*encodingFormat*/,
              dataId,
              albumId,
              true /*inTempStore*/,
              null /*uploadedTime*/));
    }
  }

  /**
   * Returns the fully qualified path within the zip archive, therefore it's unique within this
   * archive. eg: "Takeout/Google Photos/Besties/Hippo the Dog.jpg" "Takeout/Google
   * Photos/metadata.json"
   */
  // NOTE: this is purposely a method in case we change our mind (eg: decide we want to include crc
  // a la `format("%x", format("%x-%s", zipEntry.getCrc(), zipEntry.getName()).hashCode())`)
  private static String buildDataId(ZipEntry zipEntry) {
    return zipEntry.getName();
  }

  private void storeMediaBytesInTempStore(ZipInputStream zipStream, ZipEntry zipEntry)
      throws IOException {
    final String cacheKey = format("%s-%s", ZIP_ENTRY_DUPLICATE_KEY, zipEntry.getName());
    perJobStore.service().create(perJobStore.context().jobId(), cacheKey, zipStream);
  }

  private SdataMediaItemSearchResponse listAlbumlessItems(Optional<String> nextPageToken) {
    // TODO: sdata implementation: complete (_should_ be empty; but walk the tree anyway).
    // See listContainers() and listAlbumItems() implementations for inspiration.
    throw new UnsupportedOperationException("Not implemented yet: sdata implementation");
  }

  /** Returns the next zip entry or empty if the stream is exhausted. */
  private Optional<ZipEntry> getNextZipEntry(ZipInputStream stream) {
    try {
      return ofNullable(stream.getNextEntry());
    } catch (IOException e) {
      throw new IllegalStateException(
          format(
              "bad sdata stream? hit IOException mid-zip while iterating entries: %s",
              takeoutZipBaseName),
          e);
    }
  }

  /**
   * Produces a deterministically ordered stream of file paths the zip encodes.
   *
   * <p>Alternatively, for the stream variant of this same output: {@link
   * ZipEntryIterator.from(streamSdata())}.
   *
   * <p>Example: if you exhausted this stream with a continuous `getNextEntry().getName()` chain of
   * calls, it would produce output akin to `find .` on a regular directory, but very unsorted.
   */
  private ZipInputStream getZipInputStream() throws IOException, CopyExceptionWithFailureReason {
    return new ZipInputStream(SdataReader.streamSdata(takeoutZipBaseName));
  }

  /** String-friendly variant of {@link Optional#ofNullable}. */
  private static Optional<String> optionalOfString(String s) {
    return isNullOrEmpty(s) ? Optional.empty() : Optional.of(s);
  }

  private static boolean isApparentlyImage(String fileExtension) {
    return IMAGE_EXTENSIONS.contains(fileExtension.toLowerCase());
  }

  /** Two-tuple of base name and extension, if an extension is present. */
  private static Optional<Pair<String, String>> splitOnFileExtension(String filePath) {
    final int lastDotIndex = filePath.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return Optional.empty();
    }
    final String baseName = filePath.substring(0, lastDotIndex);
    final String extension = filePath.substring(lastDotIndex + 1);
    return Optional.of(Pair.of(baseName, extension));
  }

  private static boolean mightHaveFileExtension(String filePath) {
    return splitOnFileExtension(filePath).isPresent();
  }
}
