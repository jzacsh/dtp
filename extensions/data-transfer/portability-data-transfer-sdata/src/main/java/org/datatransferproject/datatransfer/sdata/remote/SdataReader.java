package org.datatransferproject.datatransfer.sdata.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static org.datatransferproject.spi.api.transport.DiscardingStreamCounter.discardForLength;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.datatransferproject.spi.transfer.types.CopyExceptionWithFailureReason;

/**
 * Reads "sdata"-standard format from a stream, likely a remote resource, and translates to local
 * types that we know can be converted to DTP models.
 */
public interface SdataReader<ContainerT, ContainerPageT, ItemT, ItemPageT> {
  /* Produce a resource path for a given itemSubPath that expected to be stored in the sdata dir. */
  public static String toResourcePath(String itemSubPath) {
    return format("config/sdata/%s", itemSubPath);
  }

  public static InputStream streamSdata(String sdataSubPath)
      throws IOException, CopyExceptionWithFailureReason {
    final String sdataResourcePath = toResourcePath(sdataSubPath);
    InputStream stream = SdataReader.class.getClassLoader().getResourceAsStream(sdataResourcePath);
    return checkNotNull(stream, "Could not find sdata resource at path: %s", sdataResourcePath);
  }

  /** Does basic validation of sdata's contents. */
  public default void assertSdataExists(String sdataSubPath)
      throws IOException, CopyExceptionWithFailureReason {
    assertSdataNonEmpty(sdataSubPath);
  }

  static void assertSdataNonEmpty(String sdataSubPath) {
    try (InputStream stream = streamSdata(sdataSubPath)) {
      checkState(
          discardForLength(stream) > 0, "sdata should be a non-empty file: %s", sdataSubPath);
    } catch (CopyExceptionWithFailureReason | IOException e) {
      throw new IllegalStateException(
          format("bad sdata stream? was just counting bytes for file: %s", sdataSubPath), e);
    }
  }

  /** Lists all containers. */
  ContainerPageT listContainers(Optional<String> nextPageToken)
      throws IOException, CopyExceptionWithFailureReason;

  /** Gets the details of a specific container. */
  ContainerT getContainer(String containerId) throws IOException, CopyExceptionWithFailureReason;

  /**
   * Lists items in a container or if no containerId is specified: items that are immediate children
   * of the root container of the user's account.
   */
  ItemPageT listItems(Optional<String> containerId, Optional<String> nextPageToken)
      throws IOException, CopyExceptionWithFailureReason;

  /** Gets a specific media item. */
  ItemT getItem(String itemId) throws IOException, CopyExceptionWithFailureReason;
}
