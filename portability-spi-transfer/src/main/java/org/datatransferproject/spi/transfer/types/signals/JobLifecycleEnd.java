package org.datatransferproject.spi.transfer.types.signals;

import com.google.auto.value.AutoValue;

/* DO NOT MERGE - document me */
@AutoValue
public abstract class JobLifecycleEnd {
  static Builder builder() {
    return AutoValue_JobLifecycleEnd.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    abstract JobLifecycleEnd build();
  }

  /* DO NOT MERGE - fill out  from PR discussions */
}
