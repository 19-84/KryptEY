package com.amnesica.kryptey.inputmethod.signalprotocol.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.SenderKey;
import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sender-key map survives a round trip, including when the peer's name contains dots.
 *
 * <p>Neither half of this codec could ever have run. The serializer called {@code writeStartObject}
 * in a map-key position, which Jackson refuses outright, so a non-empty sender-key store could not
 * be written at all; and the deserializer split the key on its first dots and ran
 * {@code Integer.parseInt} on whatever landed in the middle, which throws unchecked out of a
 * deserializer on the store-load path. Nothing noticed because the store is only populated by
 * libsignal's group-session API and this app never calls it — so the map is always empty.
 *
 * <p>That makes it a landmine rather than dead code: the day group messaging lands, every account
 * save throws. The dotted-name case is the one that matters for the read half, because the address
 * name is peer-supplied and the wire format explicitly permits dots in it.
 */
public class SenderKeyMapKeyTest {

  private static Map<SenderKey, String> roundTrip(final SenderKey key) throws IOException {
    final Map<SenderKey, String> original = new LinkedHashMap<>();
    original.put(key, "state");
    final String json = JsonUtil.toJson(original);
    assertTrue("serializing a non-empty sender-key map must produce something", json != null);
    return JsonUtil.fromJson(json, new TypeReference<LinkedHashMap<SenderKey, String>>() { });
  }

  @Test
  public void anordinarySenderKeySurvivesAroundTrip() throws Exception {
    final SenderKey key = new SenderKey("peer", 7, "3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    final Map<SenderKey, String> back = roundTrip(key);

    assertEquals(1, back.size());
    final SenderKey restored = back.keySet().iterator().next();
    assertEquals("peer", restored.getSignalProtocolAddressName());
    assertEquals(7, restored.getDeviceId());
    assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", restored.getDistributionId());
  }

  /** The case the old parser got wrong: dots in the peer-supplied name. */
  @Test
  public void adottedNameIsAttributedToTheNameAndNotToTheDeviceId() throws Exception {
    final SenderKey key =
        new SenderKey("bob.name.with.dots", 5, "3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    final SenderKey restored = roundTrip(key).keySet().iterator().next();

    assertEquals("the name must come back whole - splitting on the first dot put part of it in the "
        + "device id and threw NumberFormatException out of a deserializer",
        "bob.name.with.dots", restored.getSignalProtocolAddressName());
    assertEquals(5, restored.getDeviceId());
    assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", restored.getDistributionId());
  }

  /** And a malformed key is refused as a checked failure, not an unchecked one. */
  @Test
  public void amalformedKeyIsRefusedRatherThanThrowingUnchecked() {
    for (final String malformed : new String[] {"nodots", "one.two", "name.notanumber.dist", ""}) {
      try {
        JsonUtil.fromJson("{\"" + malformed + "\":\"state\"}",
            new TypeReference<LinkedHashMap<SenderKey, String>>() { });
        fail("a malformed sender key was accepted: " + malformed);
      } catch (final IOException expected) {
        // Checked, which is what a store-load path can handle.
      } catch (final RuntimeException unchecked) {
        fail("a malformed sender key threw unchecked out of a deserializer: " + unchecked);
      }
    }
  }
}
