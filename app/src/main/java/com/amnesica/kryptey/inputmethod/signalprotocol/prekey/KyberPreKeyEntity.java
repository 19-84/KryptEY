package com.amnesica.kryptey.inputmethod.signalprotocol.prekey;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.kem.KEMPublicKey;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * The Kyber-1024 pre-key half of a PQXDH bundle: id, KEM public key, and the signature over that
 * public key made with the sender's identity key.
 *
 * <p>Kept separate from {@link SignedPreKeyEntity} because the key type differs — {@link
 * KEMPublicKey}, not {@code ECPublicKey} — and because a Kyber public key is ~1.5 KB rather than
 * 33 bytes, which materially changes the size of a bundle sent through a messenger.
 */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
public class KyberPreKeyEntity {

  @JsonProperty
  private int keyId;

  @JsonProperty
  @JsonSerialize(using = KemPublicKeySerializer.class)
  @JsonDeserialize(using = KemPublicKeyDeserializer.class)
  private KEMPublicKey publicKey;

  @JsonProperty
  @JsonSerialize(using = ByteArraySerializer.class)
  @JsonDeserialize(using = ByteArrayDeserializer.class)
  private byte[] signature;

  public KyberPreKeyEntity() {
    // default constructor for serialization
  }

  public KyberPreKeyEntity(int keyId, KEMPublicKey publicKey, byte[] signature) {
    this.keyId = keyId;
    this.publicKey = publicKey;
    this.signature = signature;
  }

  public int getKeyId() {
    return keyId;
  }

  public KEMPublicKey getPublicKey() {
    return publicKey;
  }

  public byte[] getSignature() {
    return signature;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    KyberPreKeyEntity that = (KyberPreKeyEntity) o;
    return keyId == that.keyId
        && Arrays.equals(signature, that.signature)
        && Objects.equals(serializedOrNull(publicKey), serializedOrNull(that.publicKey));
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(keyId, serializedOrNull(publicKey));
    result = 31 * result + Arrays.hashCode(signature);
    return result;
  }

  private static String serializedOrNull(final KEMPublicKey key) {
    return key == null ? null : Base64.encodeBytesWithoutPadding(key.serialize());
  }

  private static class KemPublicKeySerializer extends JsonSerializer<KEMPublicKey> {
    @Override
    public void serialize(KEMPublicKey value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(Base64.encodeBytesWithoutPadding(value.serialize()));
    }
  }

  private static class KemPublicKeyDeserializer extends JsonDeserializer<KEMPublicKey> {
    @Override
    public KEMPublicKey deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      try {
        return new KEMPublicKey(Base64.decodeWithoutPadding(p.getValueAsString()));
      } catch (InvalidKeyException e) {
        throw new IOException(e);
      }
    }
  }

  private static class ByteArraySerializer extends JsonSerializer<byte[]> {
    @Override
    public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(Base64.encodeBytesWithoutPadding(value));
    }
  }

  private static class ByteArrayDeserializer extends JsonDeserializer<byte[]> {
    @Override
    public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      return Base64.decodeWithoutPadding(p.getValueAsString());
    }
  }
}
