/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.amnesica.kryptey.inputmethod.latin.common;

/**
 * Emojis are supplementary characters expressed as a surrogate pair. The emoji U+1F625 is encoded
 * as "\uD83D\uDE25" in UTF-16: '\uD83D' is the LEADING surrogate, in [0xD800, 0xDBFF], and
 * '\uDE25' is the TRAILING one, in [0xDC00, 0xDFFF].
 *
 * <p>The names here used to be inverted: the leading range was called "low" and the trailing one
 * "high", which is the opposite of Unicode's own terms (a leading surrogate IS the high surrogate).
 * The one call site was and is correct - it asks "leading then trailing", which is what the
 * inverted names happened to spell - so this is a rename with no behaviour in it, done because a
 * reader checking the pair against the Unicode standard would conclude the code was wrong when it
 * is not.
 *
 * {@see http://docs.oracle.com/javase/6/docs/api/java/lang/Character.html#unicode}
 */
public final class UnicodeSurrogate {
  private static final char LEADING_SURROGATE_MIN = '\uD800';
  private static final char LEADING_SURROGATE_MAX = '\uDBFF';
  private static final char TRAILING_SURROGATE_MIN = '\uDC00';
  private static final char TRAILING_SURROGATE_MAX = '\uDFFF';

  /** The first char of a pair: {@code [0xD800, 0xDBFF]}, what Unicode calls a high surrogate. */
  public static boolean isLeadingSurrogate(final char c) {
    return c >= LEADING_SURROGATE_MIN && c <= LEADING_SURROGATE_MAX;
  }

  /** The second char of a pair: {@code [0xDC00, 0xDFFF]}, what Unicode calls a low surrogate. */
  public static boolean isTrailingSurrogate(final char c) {
    return c >= TRAILING_SURROGATE_MIN && c <= TRAILING_SURROGATE_MAX;
  }
}
