package org.datatransferproject.spi.transfer.types.signals;

import com.google.auto.value.AutoValue;

/* DO NOT MERGE - document me */
@AutoValue
public abstract class JobLifecycleEnd {
  static Builder builder() {
    // TODO: Fix so we don't need fully qualified name of Buider here. This is to get ./gradlew to
    // recognize the class name due to a conflict in package names for our generated code, but the
    // conflict doesn't cause any actual problems with building.
    return org.datatransferproject.spi.transfer.types.signals.AutoValue_JobLifecycleEnd.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    abstract JobLifecycleEnd build();

    /* DO NOT MERGE - fill out  from PR discussions */
  }
}
