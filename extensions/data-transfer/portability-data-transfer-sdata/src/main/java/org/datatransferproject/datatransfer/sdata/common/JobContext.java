package org.datatransferproject.datatransfer.sdata.common;

import com.google.auto.value.AutoValue;
import java.util.UUID;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

/** Per-user Transfer job information needed by every Importer and Exporter. */
@AutoValue
public abstract class JobContext {
  public abstract UUID jobId();

  public abstract TokensAndUrlAuthData authData();

  public static Builder builder() {
    return new AutoValue_JobContext.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setJobId(UUID jobId);

    public abstract Builder setAuthData(TokensAndUrlAuthData authData);

    public abstract JobContext build();
  }
}
