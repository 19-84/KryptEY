package com.amnesica.kryptey.inputmethod.signalprotocol.util;

import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.SenderKey;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.MalformedResponseException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.IntNode;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;
import java.util.ArrayList;

public class JsonUtil {

  private static final String TAG = JsonUtil.class.getSimpleName();

  private static final ObjectMapper objectMapper;

  static {
    objectMapper = new ObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addSerializer(IdentityKeyPair.class, new IdentityKeyPairSerializer());
    module.addDeserializer(IdentityKeyPair.class, new IdentityKeyPairDeserializer());
    module.addSerializer(IdentityKey.class, new IdentityKeySerializer());
    module.addDeserializer(IdentityKey.class, new IdentityKeyDeserializer());
    module.addSerializer(SignalProtocolAddress.class, new SignalProtocolAddressSerializer());
    module.addDeserializer(SignalProtocolAddress.class, new SignalProtocolAddressDeserializer());
    module.addKeySerializer(SenderKey.class, new SenderKeySerializer());
    module.addKeyDeserializer(SenderKey.class, new SenderKeyDeserializer());
    objectMapper.registerModule(module);
    objectMapper.findAndRegisterModules(); // for Instant type
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL); // ignore null values
    // objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // for pretty json
  }

  public static String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      if (!SignalProtocolMain.testIsRunning) Log.w(TAG, e);
      e.printStackTrace();
      return null;
    }
  }

  public static <T> T fromJson(String json, Class<T> clazz)
      throws IOException {
    return objectMapper.readValue(json, clazz);
  }

  public static <T> T fromJson(String json, TypeReference<T> typeRef)
      throws IOException {
    return objectMapper.readValue(json, typeRef);
  }

  public static <T> T fromJsonResponse(String json, TypeReference<T> typeRef)
      throws MalformedResponseException {
    try {
      return JsonUtil.fromJson(json, typeRef);
    } catch (IOException e) {
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public static <T> T fromJsonResponse(String body, Class<T> clazz)
      throws MalformedResponseException {
    try {
      return JsonUtil.fromJson(body, clazz);
    } catch (IOException e) {
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public static ArrayList<Contact> convertContactsList(ArrayList<Contact> classFromSharedPreferences) {
    return objectMapper.convertValue(classFromSharedPreferences, new TypeReference<ArrayList<Contact>>() {
    });
  }

  public static ArrayList<StorageMessage> convertUnencryptedMessagesList(ArrayList<StorageMessage> classFromSharedPreferences) {
    return objectMapper.convertValue(classFromSharedPreferences, new TypeReference<ArrayList<StorageMessage>>() {
    });
  }

  public static class IdentityKeySerializer extends JsonSerializer<IdentityKey> {
    @Override
    public void serialize(IdentityKey value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      Log.d(TAG, "IdentityKeySerializer used");
      gen.writeStartObject();
      gen.writeStringField("publicKey", Base64.encodeBytesWithoutPadding(value.getPublicKey().serialize()));
      gen.writeEndObject();
    }
  }

  public static class IdentityKeyDeserializer extends JsonDeserializer<IdentityKey> {
    @Override
    public IdentityKey deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      try {
        Log.d(TAG, "IdentityKeyDeserializer used");
        JsonNode node = p.getCodec().readTree(p);
        return new IdentityKey(Base64.decodeWithoutPadding(node.get("publicKey").asText()), 0);
      } catch (InvalidKeyException e) {
        throw new IOException(e);
      }
    }
  }

  public static class IdentityKeyPairSerializer extends JsonSerializer<IdentityKeyPair> {
    @Override
    public void serialize(IdentityKeyPair value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      Log.d(TAG, "IdentityKeyPairSerializer used");
      gen.writeString(Base64.encodeBytesWithoutPadding(value.serialize()));
    }
  }

  public static class IdentityKeyPairDeserializer extends JsonDeserializer<IdentityKeyPair> {
    @Override
    public IdentityKeyPair deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      Log.d(TAG, "IdentityKeyPairDeserializer used");
      try {
        return new IdentityKeyPair(Base64.decodeWithoutPadding(p.getValueAsString()));
      } catch (InvalidKeyException e) {
        // libsignal 0.86 validates the key on construction and reports a malformed one instead of
        // accepting it. Surface it as an IOException so it travels the same path as any other
        // deserialization failure rather than escaping Jackson as an unchecked exception.
        throw new IOException(e);
      }
    }
  }

  public static class SignalProtocolAddressSerializer extends JsonSerializer<SignalProtocolAddress> {
    @Override
    public void serialize(SignalProtocolAddress value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      Log.d(TAG, "SignalProtocolAddressKeySerializer used");
      gen.writeStartObject();
      gen.writeStringField("name", value.getName());
      gen.writeNumberField("deviceId", value.getDeviceId());
      gen.writeEndObject();
    }
  }

  public static class SignalProtocolAddressDeserializer extends JsonDeserializer<SignalProtocolAddress> {
    @Override
    public SignalProtocolAddress deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      Log.d(TAG, "SignalProtocolAddressDeserializer used");
      JsonNode node = p.getCodec().readTree(p);
      String name = node.get("name").asText();
      int deviceId = (Integer) ((IntNode) node.get("deviceId")).numberValue();
      // Legacy stores hold device ids up to 9999, which libsignal 0.86 refuses to construct.
      return ProtocolAddresses.of(name, deviceId);
    }
  }

  /**
   * Parses a sender key back out of a map key, from the right.
   *
   * <p>These two classes were broken in both directions and neither half could ever have run: the
   * serializer called {@code writeStartObject} in a map-key position, which Jackson refuses
   * outright, so a non-empty sender-key store could not be written at all. The store is only
   * populated by libsignal's group-session API, which this app never calls, so the map is always
   * empty and nothing noticed. That is a landmine rather than dead code — the day group messaging
   * lands, every account save throws.
   *
   * <p>Parsing from the right rather than the left, which is the other half of the bug. The
   * address name is peer-supplied and {@code requireDisplaySafeName} explicitly permits {@code .},
   * so splitting on the first dots attributes a name like {@code bob.name} to the wrong fields —
   * and the old code then ran {@code Integer.parseInt} on it and threw {@code NumberFormatException}
   * out of a deserializer, on the store-load path. The distribution id is a UUID and the device id
   * is numeric, so the last two dots are unambiguous however many the name contains.
   */
  public static class SenderKeyDeserializer extends KeyDeserializer {
    @Override
    public SenderKey deserializeKey(final String key, final DeserializationContext ctxt)
        throws IOException {
      if (key == null) throw new IOException("no sender key");
      final int lastDot = key.lastIndexOf('.');
      if (lastDot <= 0) throw new IOException("malformed sender key: " + key);
      final int secondLastDot = key.lastIndexOf('.', lastDot - 1);
      if (secondLastDot <= 0) throw new IOException("malformed sender key: " + key);

      final String name = key.substring(0, secondLastDot);
      final String deviceId = key.substring(secondLastDot + 1, lastDot);
      final String distributionId = key.substring(lastDot + 1);
      if (name.isEmpty() || deviceId.isEmpty() || distributionId.isEmpty()) {
        throw new IOException("malformed sender key: " + key);
      }
      try {
        return new SenderKey(name, Integer.parseInt(deviceId), distributionId);
      } catch (final NumberFormatException e) {
        // Unchecked out of a deserializer is how this used to fail, on the store-load path.
        throw new IOException("malformed sender key: " + key, e);
      }
    }
  }

  /** Writes a sender key as a map key, in the form {@link SenderKeyDeserializer} reads. */
  public static class SenderKeySerializer extends JsonSerializer<SenderKey> {
    @Override
    public void serialize(final SenderKey value, final JsonGenerator gen,
        final SerializerProvider serializers) throws IOException {
      gen.writeFieldName(value.getSignalProtocolAddressName() + "."
          + value.getDeviceId() + "." + value.getDistributionId());
    }
  }
}
