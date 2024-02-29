/*
 * Copyright 2023 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.datatransfer.apple.exceptions;

import org.jetbrains.annotations.NotNull;

/** An exception to be used when the content uploading / downloading fails. */
public class AppleContentException extends Exception {
  public enum ImmediateCause {
    TOO_MUCH_DATA,
    SERVER_ERROR_REPLY,
    UNKNOWN_CAUSE
  }

  private ImmediateCause immediateCause = ImmediateCause.UNKNOWN_CAUSE;

  public ImmediateCause getImmediateCause() {
    return this.immediateCause;
  }

  public AppleContentException(@NotNull final String message) {
    super(message);
  }

  public AppleContentException(@NotNull final String message, ImmediateCause immediateCause) {
    this(message);
    this.immediateCause = immediateCause;
  }

  public AppleContentException(@NotNull final String message, @NotNull Throwable throwable) {
    super(message, throwable);
  }

  public AppleContentException(
      @NotNull final String message, @NotNull Throwable throwable, ImmediateCause immediateCause) {
    this(message, throwable);
    this.immediateCause = immediateCause;
  }
}
