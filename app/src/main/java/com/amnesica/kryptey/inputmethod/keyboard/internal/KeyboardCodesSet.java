/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * limitations under the License.
 */

package com.amnesica.kryptey.inputmethod.keyboard.internal;

import com.amnesica.kryptey.inputmethod.latin.common.Constants;

import java.util.HashMap;

public final class KeyboardCodesSet {
  public static final String PREFIX_CODE = "!code/";

  private static final HashMap<String, Integer> sNameToIdMap = new HashMap<>();

  private KeyboardCodesSet() {
    // This utility class is not publicly instantiable.
  }

  public static int getCode(final String name) {
    Integer id = sNameToIdMap.get(name);
    if (id == null) throw new RuntimeException("Unknown key code: " + name);
    return DEFAULT[id];
  }

  private static final String[] ID_TO_NAME = {
      "key_tab",
      "key_enter",
      "key_space",
      "key_shift",
      "key_capslock",
      "key_switch_alpha_symbol",
      "key_output_text",
      "key_delete",
      "key_settings",
      "key_action_next",
      "key_action_previous",
      "key_shift_enter",
      "key_language_switch",
      // "key_left", "key_right" and "key_unspecified" were here, and there were no codes for them.
      //
      // getCode indexes DEFAULT with the ID_TO_NAME index, and the two arrays were 16 and 14 long -
      // so "key_left" resolved to CODE_UNSPECIFIED, which PointerTracker drops, and the other two
      // threw ArrayIndexOutOfBoundsException out of the layout parser. No shipped XML uses any of
      // the three, so nothing was wrong; what was wrong was that the next person to write
      // !code/key_unspecified - the idiomatic spelling for a key that does nothing - would have had
      // the keyboard fail to build with an out-of-bounds error rather than a "no such code" one.
      //
      // Deleted rather than given invented codes: key_left and key_right would be two key codes
      // nothing in handleFunctionalEvent handles, turning an unreachable parse-time exception into
      // a reachable "Unknown key code" at a key press. The lengths are asserted equal by
      // KeyboardCodesSetTest, which is a better guard than either, because it also catches the
      // next divergence.
  };

  private static final int[] DEFAULT = {
      Constants.CODE_TAB,
      Constants.CODE_ENTER,
      Constants.CODE_SPACE,
      Constants.CODE_SHIFT,
      Constants.CODE_CAPSLOCK,
      Constants.CODE_SWITCH_ALPHA_SYMBOL,
      Constants.CODE_OUTPUT_TEXT,
      Constants.CODE_DELETE,
      Constants.CODE_SETTINGS,
      Constants.CODE_ACTION_NEXT,
      Constants.CODE_ACTION_PREVIOUS,
      Constants.CODE_SHIFT_ENTER,
      Constants.CODE_LANGUAGE_SWITCH,
      // The trailing CODE_UNSPECIFIED went with "key_left" above: it was the value that name
      // silently resolved to, and with the name gone it is an entry nothing can index.
  };

  static {
    for (int i = 0; i < ID_TO_NAME.length; i++) {
      sNameToIdMap.put(ID_TO_NAME[i], i);
    }
  }
}
