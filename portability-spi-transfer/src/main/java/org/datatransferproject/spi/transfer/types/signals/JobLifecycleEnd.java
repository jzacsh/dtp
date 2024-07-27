package org.datatransferproject.spi.transfer.types.signals;

import static com.google.common.base.Preconditions.checkState;

import org.datatransferproject.spi.transfer.types.FailureReason;
import com.google.auto.value.AutoValue;
import java.util.Optional;

/* DO NOT MERGE - document me */
@AutoValue
public abstract class JobLifecycleEnd {
  /* DO NOT MERGE - document me */
  public enum EndReason {
    END_REASON_UNSPECIFIED,

    /** Job completed without failing transfer of any files. */
    END_PERFECT,

    /** Job completed some files skipped. */
    END_PARTIAL,
  };


  public abstract Optional<EndReason> endReason();

  /**
   * DTP framework-level error reason code that might be available.
   *
   * <p>Note: DTP's current implementation is such that this likely means the job alsoeended
   * abruptly.
   */
  public abstract Optional<FailureReason> failureCode();

  public static JobLifecycleEnd ofPerfect() {
    return builder().setEndReason(EndReason.END_PERFECT).build();
  }

  // TODO(jzacsh, sundeep) add another overload to ofPartial that takes in more metadata: "_HOW_
  // partial was it?" can be a small collection of Optional<Integer> on this class. eg: counts from
  // ErrorDetail collections. Our hesitation here is the API isn't particularly trust-worthy right
  // now to come-alone with v0 of this class. We'd want to add things like: a method that promises
  // to return the total number of errors (eg: documents being robust to restarts), a method that
  // gives the total number of _successes seen (eg: also requires augmenting some of the adapters to
  // implement, as a reference-implementation).

  public static JobLifecycleEnd ofPartial() {
    return builder().setEndReason(EndReason.END_PARTIAL).build();
  }

  public static JobLifecycleEnd ofPartial(FailureReason failureCode) {
    return builder().setEndReason(EndReason.END_PARTIAL).setFailureCode(failureCode).build();
  }

  static JobLifecycleEnd.Builder builder() {
    // TODO: Fix so we don't need fully qualified name of Buider here. This is to get ./gradlew to
    // recognize the class name due to a conflict in package names for our generated code, but the
    // conflict doesn't cause any actual problems with building.
    return org.datatransferproject.spi.transfer.types.signals.AutoValue_JobLifecycleEnd.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    /* DO NOT MERGE - fill out anything else from PR discussions and/or doc */

    abstract Builder setFailureCode(FailureReason failureCode);

    abstract Builder setEndReason(EndReason endReason);

    abstract JobLifecycleEnd autoBuild();

    public final JobLifecycleEnd build() {
      JobLifecycleEnd jobLifecycleEnd = autoBuild();
      checkState(
          !jobLifecycleEnd.hasEndReason(EndReason.END_REASON_UNSPECIFIED),
          "either end reason should be ommitted, or it should be specified to something logical");
      checkState(
          !jobLifecycleEnd.failureCode.isPresent() ||
              !jobLifecycleEnd.hasEndReason(EndReason.END_PERFECT),
          "job cannot have ended perfectly but also have an failure code");
      return jobLifecycleEnd;
    }
  }

  private boolean hasEndReason(EndReason target) {
    return endReason().isPresent() && endReason().get().equals(target),
  }
}
