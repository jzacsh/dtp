package org.datatransferproject.spi.transfer.types.signals;

import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import java.util.Optional;

/* DO NOT MERGE - document me */
@AutoValue
public abstract class JobLifecycleUpdate {
  /**
   * A particular state in the lifecycle of a job.
   *
   * <p>Warning: be careful not to conflate this enum with concerns of _quality_ of state. That's
   * what metadata fields of {@link JobLifecycleUpdate} (like {@link JobLifecycleEnd}) are intended
   * to capture.
   */
  public enum JobLifecycle {
    /* DO NOT MERGE - rename from SignalType */
    /* DO NOT MERGE - document each of these */

    JOB_UNSPECIFIED, /* DO NOT MERGE - rename _all_ to use full prefix JOB_LIFECYCLE_ */

    JOB_BEGIN,
    JOB_ERRORED, /* DO NOT MERGE - delete these refs */
    JOB_ENDED /* DO NOT MERGE - renmae from JOB_COMPLETED */
  }

  public static Builder builder() {
    // TODO: Fix so we don't need fully qualified name of Buider here. This is to get ./gradlew to
    // recognize the class name due to a conflict in package names for our generated code, but the
    // conflict doesn't cause any actual problems with building.
    return org.datatransferproject.spi.transfer.types.signals.AutoValue_JobLifecycleUpdate.Builder()
        .setJobLifecycle(JobLifecycle.JOB_UNSPECIFIED);
  }

  public static JobLifecycleUpdate ofStart() {
    return builder().setJobLifecycle(JobLifecycle.JOB_BEGIN).build();
  }

  public static JobLifecycleUpdate ofEnd() {
    return builder().setJobLifecycle(JobLifecycle.JOB_ENDED).build();
  }

  public static JobLifecycleUpdate ofEnd(JobLifecycleEnd jobLifecycleEnd) {
    return builder()
        .setJobLifecycle(JobLifecycle.JOB_ENDED)
        .setJobLifecycleEnd(jobLifecycleEnd)
        .build();
  }

  /** Returns the particular state, within the broader lifecycle of a job, this job is in. */
  public abstract JobLifecycle jobLifecycle();

  /**
   * Returns optional metadata about jobs in the end-state, if available.
   *
   * <p>When {@link JobLifecycle.JOB_ENDED}, then this might be present to add more metadata about
   * the end-state of the job (eg: qualifications about the health of the job like number of failed
   * files).
   */
  public abstract Optional<JobLifecycleEnd> jobLifecycleEnd();

  @AutoValue.Builder
  public abstract static class Builder {
    abstract JobLifecycleUpdate autoBuild();

    public final JobLifecycleUpdate build() {
      JobLifecycleUpdate jobLifecycleUpdate = autoBuild();
      checkState(
          !jobLifecycleUpdate.jobLifecycle().equals(JobLifecycle.JOB_UNSPECIFIED),
          "JobLifecycle enum is required");
      checkState(
          !jobLifecycleUpdate.jobLifecycleEnd().isPresent()
              || jobLifecycleUpdate.jobLifecycle().equals(JobLifecycle.JOB_ENDED),
          "JobLifecycleEnd metadata is only designed for JobLifecycle.JOB_ENDED state");
      return jobLifecycleUpdate;
    }

    public abstract Builder setJobLifecycle(JobLifecycle jobLifecycle);

    public abstract Builder setJobLifecycleEnd(JobLifecycleEnd jobLifecycleEnd);
  }
}
