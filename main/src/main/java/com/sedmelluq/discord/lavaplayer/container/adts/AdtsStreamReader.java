package com.sedmelluq.discord.lavaplayer.container.adts;

import com.sedmelluq.discord.lavaplayer.tools.io.BitBufferReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Finds and reads ADTS packet headers from an input stream.
 */
public class AdtsStreamReader {
  private static final AdtsPacketHeader EOF_PACKET = new AdtsPacketHeader(false, 0, 0, 0, 0);

  private static final int HEADER_BASE_SIZE = 7;
  private static final int INVALID_VALUE = -1;

  private static final int[] sampleRateMapping = new int[]{
          96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
          16000, 12000, 11025, 8000, 7350, INVALID_VALUE, INVALID_VALUE, INVALID_VALUE
  };

  private final InputStream inputStream;
  private final byte[] scanBuffer;
  private final ByteBuffer scanByteBuffer;
  private final BitBufferReader scanBufferReader;
  private AdtsPacketHeader currentPacket;

  /**
   * @param inputStream The input stream to use.
   */
  public AdtsStreamReader(InputStream inputStream) {
    this.inputStream = inputStream;
    this.scanBuffer = new byte[1024]; // Increase buffer size to read more data
    this.scanByteBuffer = ByteBuffer.wrap(scanBuffer);
    this.scanBufferReader = new BitBufferReader(scanByteBuffer);
  }

  /**
   * Scan the input stream for an ADTS packet header. Subsequent calls will return the same header until nextPacket() is
   * called.
   *
   * @return The packet header if found before EOF.
   * @throws IOException On read error.
   */
  public AdtsPacketHeader findPacketHeader() throws IOException {
    return findPacketHeader(Integer.MAX_VALUE);
  }

  /**
   * Scan the input stream for an ADTS packet header. Subsequent calls will return the same header until nextPacket() is
   * called.
   *
   * @param maximumDistance Maximum number of bytes to scan.
   * @return The packet header if found before EOF and maximum byte limit.
   * @throws IOException On read error.
   */
  public AdtsPacketHeader findPacketHeader(int maximumDistance) throws IOException {
    if (currentPacket == null) {
      currentPacket = scanForPacketHeader(maximumDistance);
    }

    return currentPacket == EOF_PACKET ? null : currentPacket;
  }

  /**
   * Resets the current packet, makes next calls to findPacketHeader scan for the next occurrence in the stream.
   */
  public void nextPacket() {
    currentPacket = null;
  }

  private AdtsPacketHeader scanForPacketHeader(int maximumDistance) throws IOException {
    int bufferPosition = 0;
    boolean isID3 = false;
    int id3BytesToSkip = 0;

    while (true) {
      int nextByte = inputStream.read();

      if (nextByte == -1) {
        System.out.println("Reached end of stream while scanning for packet header.");
        return EOF_PACKET;
      }

      scanBuffer[bufferPosition++] = (byte) nextByte;

      if (bufferPosition >= 3 && scanBuffer[0] == 'I' && scanBuffer[1] == 'D' && scanBuffer[2] == '3') {
        isID3 = true;
      }

      if (isID3 && bufferPosition >= 10) {
        // Calculate the size of the ID3 tag if not already calculated
        if (id3BytesToSkip == 0) {
          int id3Size = (scanBuffer[6] & 0x7F) << 21 | (scanBuffer[7] & 0x7F) << 14 | (scanBuffer[8] & 0x7F) << 7 | (scanBuffer[9] & 0x7F);
          id3BytesToSkip = id3Size + 10; // Add 10 for the header itself
          System.out.println("ID3 tag found. Size: " + id3Size);
          System.out.println("ID3 tag content: " + Arrays.toString(Arrays.copyOfRange(scanBuffer, 0, Math.min(id3BytesToSkip, scanBuffer.length))));
        }

        // Skip the remaining bytes of the ID3 tag
        int bytesToSkip = id3BytesToSkip - bufferPosition;
        while (bytesToSkip > 0) {
          int skipped = (int) inputStream.skip(bytesToSkip);
          bytesToSkip -= skipped;
        }

        bufferPosition = 0;
        isID3 = false;
        id3BytesToSkip = 0;
        continue;
      }

      if (bufferPosition >= HEADER_BASE_SIZE) {
        AdtsPacketHeader header = readHeaderFromBufferTail(bufferPosition);

        if (header != null) {
          System.out.println("Found ADTS packet header: " + header);
          return header;
        }
      }

      if (bufferPosition == scanBuffer.length) {
        copyEndToBeginning(scanBuffer, HEADER_BASE_SIZE);
        bufferPosition = HEADER_BASE_SIZE;
      }
    }
  }


  private AdtsPacketHeader readHeaderFromBufferTail(int position) throws IOException {
    scanByteBuffer.position(position - HEADER_BASE_SIZE);

    AdtsPacketHeader header = readHeader(scanBufferReader);
    scanBufferReader.readRemainingBits();

    if (header == null) {
      return null;
    } else if (!header.isProtectionAbsent) {
      int crcFirst = inputStream.read();
      int crcSecond = inputStream.read();

      if (crcFirst == -1 || crcSecond == -1) {
        System.out.println("Reached end of stream while reading CRC.");
        return EOF_PACKET;
      }
    }

    return header;
  }

  private static void copyEndToBeginning(byte[] buffer, int chunk) {
    for (int i = 0; i < chunk; i++) {
      buffer[i] = buffer[buffer.length - chunk + i];
    }
  }

  
  private static AdtsPacketHeader readHeader(BitBufferReader reader) {
    if ((reader.asLong(15) & 0x7FFB) != 0x7FF8) {
      System.out.println("Failed to find syncword and layer bits in header.");
      return null;
    }

    boolean isProtectionAbsent = reader.asLong(1) == 1;
    int profile = reader.asInteger(2);
    int sampleRate = sampleRateMapping[reader.asInteger(4)];

    // Private bit
    reader.asLong(1);

    int channels = reader.asInteger(3);

    if (sampleRate == INVALID_VALUE || channels == 0) {
      System.out.println("Invalid sample rate or channel count in header.");
      return null;
    }

    // 4 boring bits
    reader.asLong(4);

    int frameLength = reader.asInteger(13);
    int payloadLength = frameLength - 7 - (isProtectionAbsent ? 0 : 2);

    // More boring bits
    reader.asLong(11);

    if (reader.asLong(2) != 0) {
      System.out.println("Multiple frames per packet not supported.");
      return null;
    }

    return new AdtsPacketHeader(isProtectionAbsent, profile + 1, sampleRate, channels, payloadLength);
  }
}
