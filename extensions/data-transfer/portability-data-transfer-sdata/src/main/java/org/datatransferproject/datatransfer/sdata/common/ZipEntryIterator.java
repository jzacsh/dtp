package org.datatransferproject.datatransfer.sdata.common;

import static java.util.Optional.ofNullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adapts an zip archive from a java.io.InputStream to a java.util.stream.Stream<ZipEntry>.
 *
 * <p>Example Usage:
 *
 * <pre><code>
 * try (InputStream zipStream = new java.io.FileInputStream("/tmp/test.zip")) {
 *   ZipEntryStream
 *       .from(zipStream)
 *       .sequential() // done by default
 *       .map(ZipEntry::getName)
 *       .filter(name -> name.contains(".json"))
 *       .limit(10)
 *       .forEach(jsonFilePath -> System.out.format("json filename: \"%s\"\n", jsonFilePath));
 * }
 * </pre></code>
 */
public final class ZipEntryIterator implements Spliterator<ZipEntry> {
  private final ZipInputStream zipStream;

  public ZipEntryIterator(InputStream inputStream) {
    this.zipStream = new ZipInputStream(inputStream);
  }

  public static Stream<ZipEntry> from(InputStream inputStream) {
    return StreamSupport.stream(new ZipEntryIterator(inputStream), false /*parallel*/).sequential();
  }

  public long estimateSize() {
    return Long.MAX_VALUE; // Spliterator's API to indicate: unknown size
  }

  public int characteristics() {
    return Spliterator.IMMUTABLE | Spliterator.NONNULL;
  }

  public ZipEntryIterator trySplit() {
    return null; // Spliterator's API to indicate: we don't support partitioning.
  }

  public boolean tryAdvance(Consumer<? super ZipEntry> action) {
    Optional<ZipEntry> nextEntry;
    try {
      nextEntry = ofNullable(zipStream.getNextEntry());
    } catch (IOException e) {
      throw new IllegalStateException(
          "malformed zip archive, or disk error? IOException while getting next entry", e);
    }
    if (nextEntry.isPresent()) {
      action.accept(nextEntry.get());
      return true;
    } else {
      return false;
    }
  }
}
