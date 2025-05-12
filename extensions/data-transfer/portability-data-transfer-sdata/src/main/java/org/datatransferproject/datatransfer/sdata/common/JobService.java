package org.datatransferproject.datatransfer.sdata.common;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class JobService<S> {
  public abstract JobContext context();

  public abstract S service();

  public static <S> Builder<S> builder() {
    return new AutoValue_JobService.Builder<S>();
  }

  @AutoValue.Builder
  public abstract static class Builder<S> {
    public abstract Builder<S> setContext(JobContext context);

    public abstract Builder<S> setService(S service);

    public abstract JobService<S> build();
  }
}
