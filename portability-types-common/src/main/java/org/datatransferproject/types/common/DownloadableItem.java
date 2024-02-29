package org.datatransferproject.types.common;

import static com.google.common.base.Preconditions.checkState;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Represent an item we can download through a URL and store in a temporary storage. PhotoModel is a
 * good example. Often, we check if the item is in the job store and download it if it isn't.
 */
public interface DownloadableItem extends ImportableItem {

  /**
   * @Deprecated prefer {@link #getFetchableURL} *
   */
  String getFetchableUrl();

  @JsonIgnore
  default URL getFetchableURL() throws IllegalStateException {
    String url = this.getFetchableUrl();
    checkState(
        !Strings.isNullOrEmpty(url), "URL construction impossible: missing URL (got \"%s\")", url);
    try {
      return new URL(url);
    } catch (MalformedURLException e) {
      throw new IllegalStateException(
          String.format("DownloadableItem constructed with bad URL \"%s\"", url), e);
    }
  }

  boolean isInTempStore();
}
