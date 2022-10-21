package org.datatransferproject.datatransfer.google.music;
// DO NOT MERGE move this to a common folder

import org.apache.commons.lang3.tuple.Pair;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.datatransferproject.types.common.PaginationData;
import org.datatransferproject.types.common.StringPaginationToken;

/**
 * DO NOT MERGE; explain why we use "page" as in node in a paginated set of request
 */
// DO NOT MERGE use AutoValue instead?
// TODO rename PaginationData since now that name is the same (and/or rename this class) and update
// both class's documentation.
// DO NOT MERGE alternatively, use PaginationData but also utilize JSON, then we don't need this
// class (we're currently parsing strings, which is what the JSON libraries do better).
final class TransferPageInfo<ResourceType> {

  private final String serializedOriginal;

  private final ResourceType resourceType;
  private final Optional<String> apiPagingToken;

  private TransferPageInfo(
      ResourceType resourceType,
      Optional<String> apiPagingToken,
      String serializedOriginal) {
    this.resourceType = resourceType;
    this.apiPagingToken = apiPagingToken;
    this.serializedOriginal = serializedOriginal;
  }

  public ResourceType getResourceType() {
    return resourceType;
  }

  public Optional<String> getApiPagingToken() {
    return apiPagingToken;
  }

  /* DO NOT MERGE explain this is for debugging */
  public String getSerializedOriginal() {
    return serializedOriginal;
  }

  /**
   * Returns the two-tuple of "prefix" and an optional "next page token."
   *
   * DO NOT MERGE: explain what those two concepts are, or better yet write it down somewhere
   * canonical and point this doc over there.
   */
  // DO NOT MERGE if we convert this class to a mix of AutoValue and JSON parsing, AND we agree we
  // don't need the custom strings in TokenPrefix anymore (that is: we can delete the constructor
  // from TokenPrefix, and instead rely on Enum::toString() and Enum::valueOf() to convert in and
  // out of a given enum (a thin that extends java.lang.Enum). Then: take the moment to replace
  // lookupResourceType with MyEnum::valueOf if we get rid of the string
  public static <R> Optional<TransferPageInfo<R>> of(PaginationData paginationData, Function<String, R> lookupResourceType) {
    if (paginationData == null) {
      return Optional.empty();
    }
    final StringPaginationToken paginationToken = (StringPaginationToken) paginationData;
    if (paginationToken == null) {
      return Optional.empty();
    }

    // a string of two concatenated parts: a known prefix (see knownPrefixes) followed by an API
    // pagination token. eg: "foo-prefix:a2fe320", "baz:thing:prefix:a2fe320"
    final String serializedOriginal = paginationToken.getToken();
    if (Strings.isNullOrEmpty(serializedOriginal)) {
      return Optional.empty();
    }

    Pair<String, Optional<String>> prefixAndToken = splitRightMostDelimeter(serializedOriginal);

    R resourceType = lookupResourceType.apply(prefixAndToken.getLeft());

    return Optional.of(new TransferPageInfo(resourceType, prefixAndToken.getRight(), serializedOriginal));
  }

  private static Pair<String, Optional<String>> splitRightMostDelimeter(String input) {
    // DO NOT MERGE - we're assuming colon-delimited here; tackle when we figure out where to put
    // most of TokenPrefix's abstraction. If we stop custom hackery with TokenPrefix and just use
    // normal enums, then we can just move this to private stateic const on this class.
    int lastColonIndex = input.lastIndexOf(":");
    Preconditions.checkState(lastColonIndex != -1, "no valid token prefix found in pagination data ('%s')", input);
    String tokenPrefix = input.substring(0, lastColonIndex + 1);
    boolean hasTrailingContent = lastColonIndex != input.length() - 1;
    Optional<String> nextPageToken = hasTrailingContent
        ? Optional.of(input.substring(lastColonIndex + 1))
        : Optional.empty();
    return Pair.of(tokenPrefix, nextPageToken);
  }

  public StringPaginationToken toNewStringToken(String nextPageToken) {
    return new StringPaginationToken(this.getResourceType().toString().concat(nextPageToken));
  }
}
