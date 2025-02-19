package com.sedmelluq.discord.lavaplayer.source.stream;

import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.util.EntityUtils;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;
import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.fetchResponseLines;

/**
 * Provides track segment URLs for streams which use the M3U segment format. There is a base M3U containing the list of
 * different available streams. Those point to segment M3U urls, which always give the direct stream URLs of last X
 * segments. The segment provider fetches the stream for the next segment on each call to
 * {@link M3uStreamSegmentUrlProvider#getNextSegmentStream}.
 */
public abstract class M3uStreamSegmentUrlProvider {
  private static final long SEGMENT_WAIT_STEP_MS = 200;
  private static final RequestConfig streamingRequestConfig = RequestConfig.custom().setSocketTimeout(5000).setConnectionRequestTimeout(5000).setConnectTimeout(5000).build();

  protected String baseUrl;
  protected SegmentInfo lastSegment;
  protected Cipher cipher;

  protected M3uStreamSegmentUrlProvider() {
    this(null);
  }

  protected M3uStreamSegmentUrlProvider(String originUrl) {
    if (originUrl != null) {
      if (originUrl.endsWith("/")) {
        originUrl = originUrl.substring(0, originUrl.length() - 1);
      }

      this.baseUrl = originUrl.substring(0, originUrl.lastIndexOf("/"));
    } else {
      this.baseUrl = null;
    }
  }

  protected static String createSegmentUrl(String playlistUrl, String segmentName) {
    return URI.create(playlistUrl).resolve(segmentName).toString();
  }

  /**
   * If applicable, extracts the quality information from the M3U directive which describes one stream in the root M3U.
   *
   * @param directiveLine Directive line with arguments.
   * @return The quality name extracted from the directive line.
   */
  protected abstract String getQualityFromM3uDirective(ExtendedM3uParser.Line directiveLine);

  protected abstract String fetchSegmentPlaylistUrl(HttpInterface httpInterface) throws IOException;

  /**
   * Logic for getting the URL for the next segment.
   *
   * @param httpInterface HTTP interface to use for any requests required to perform to find the segment URL.
   * @return The direct stream URL of the next segment.
   */
  protected String getNextSegmentUrl(HttpInterface httpInterface) {
    try {
      String streamSegmentPlaylistUrl = fetchSegmentPlaylistUrl(httpInterface);

      if (streamSegmentPlaylistUrl == null) {
        return null;
      }

      long startTime = System.currentTimeMillis();
      SegmentInfo nextSegment;

      while (true) {
        List<SegmentInfo> segments = loadStreamSegmentsList(httpInterface, streamSegmentPlaylistUrl);
        nextSegment = chooseNextSegment(segments, lastSegment);

        if (nextSegment != null || !shouldWaitForSegment(startTime, segments)) {
          break;
        }

        Thread.sleep(SEGMENT_WAIT_STEP_MS);
      }

      if (nextSegment == null) {
        return null;
      }

      lastSegment = nextSegment;
      return createSegmentUrl(streamSegmentPlaylistUrl, lastSegment.url);
    } catch (IOException | URISyntaxException e) {
      throw new FriendlyException("Failed to get next part of the stream.", SUSPICIOUS, e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void initializeCipher(HttpInterface httpInterface, String uri) throws IOException {
    try (CloseableHttpResponse httpResponse = httpInterface.execute(new HttpGet(uri))) {
      HttpClientTools.assertSuccessWithContent(httpResponse, "retrieve cipher key");

      byte[] cipherKey = EntityUtils.toByteArray(httpResponse.getEntity());
      SecretKeySpec keySpec = new SecretKeySpec(cipherKey, "AES");
      byte[] iv = new byte[16]; // default IV of all zeros

      cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
      System.out.println("Cipher initialized successfully with key: " + Arrays.toString(cipherKey) + " and IV: " + Arrays.toString(iv));
    } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException | InvalidAlgorithmParameterException e) {
      throw new RuntimeException("Error initializing cipher", e);
    }
  }

  /**
   * Fetches the input stream for the next segment in the M3U stream.
   *
   * @param httpInterface HTTP interface to use for any requests required to perform to find the segment URL.
   * @return Input stream of the next segment.
   */
  public InputStream getNextSegmentStream(HttpInterface httpInterface) {
    httpInterface.getContext().setRequestConfig(streamingRequestConfig);
    String url = getNextSegmentUrl(httpInterface);
    if (url == null) {
      System.err.println("No URL for the next segment found.");
      return null;
    }

    System.out.println("Fetching segment from URL: " + url);
    CloseableHttpResponse response = null;

    try {
      response = httpInterface.execute(createSegmentGetRequest(url));
      HttpClientTools.assertSuccessWithContent(response, "segment data URL");

      InputStream segmentStream = response.getEntity().getContent();
      System.out.println("Segment stream obtained, starting decryption...");

      InputStream cipherStream = new CipherInputStream(segmentStream, cipher);
      System.out.println("Cipher stream created.");

      // Read and log the first few bytes of decrypted data for inspection
      CloseableHttpResponse finalResponse = response;
      InputStream logStream = new InputStream() {
        private final byte[] buffer = new byte[32];
        private int bytesRead = 0;

        @Override
        public int read() throws IOException {
          int byteRead = cipherStream.read();
          if (bytesRead < buffer.length) {
            buffer[bytesRead++] = (byte) byteRead;
          }
          return byteRead;
        }

        @Override
        public int read(byte[] b) throws IOException {
          int len = cipherStream.read(b);
          if (bytesRead < buffer.length) {
            int toCopy = Math.min(buffer.length - bytesRead, len);
            System.arraycopy(b, 0, buffer, bytesRead, toCopy);
            bytesRead += toCopy;
          }
          return len;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          int bytesRead = cipherStream.read(b, off, len);
          if (this.bytesRead < buffer.length) {
            int toCopy = Math.min(buffer.length - this.bytesRead, bytesRead);
            System.arraycopy(b, off, buffer, this.bytesRead, toCopy);
            this.bytesRead += toCopy;
          }
          return bytesRead;
        }

        @Override
        public void close() throws IOException {
          cipherStream.close();
          finalResponse.close();
          System.out.println("Segment and response streams closed.");
          System.out.println("Decrypted first few bytes: " + Arrays.toString(buffer));
        }
      };

      return logStream;
    } catch (IOException e) {
      System.err.println("Error fetching or decrypting segment data: " + e.getMessage());
      throw new RuntimeException("Error fetching segment data", e);
    }
  }

  protected abstract HttpUriRequest createSegmentGetRequest(String url);

  protected boolean isAbsoluteUrl(String url) {
    try {
      return this.baseUrl == null || new URI(url).isAbsolute();
    } catch (URISyntaxException e) {
      return false;
    }
  }

  protected String getAbsoluteUrl(String url) {
    return baseUrl + (url.startsWith("/") ? url : "/" + url);
  }

  protected List<ChannelStreamInfo> loadChannelStreamsList(String[] lines) {
    ExtendedM3uParser.Line streamInfoLine = null;

    List<ChannelStreamInfo> streams = new ArrayList<>();

    for (String lineText : lines) {
      ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(lineText);

      if (line.isData() && streamInfoLine != null) {
        String quality = getQualityFromM3uDirective(streamInfoLine);
        if (quality != null) {
          streams.add(new ChannelStreamInfo(quality, isAbsoluteUrl(line.lineData) ? line.lineData : getAbsoluteUrl(line.lineData)));
        }

        streamInfoLine = null;
      } else if (line.isDirective() && ("EXT-X-STREAM-INF".equals(line.directiveName) || "EXTINF".equals(line.directiveName))) {
        streamInfoLine = line;
      }
    }

    return streams;
  }

  protected List<SegmentInfo> loadStreamSegmentsList(HttpInterface httpInterface, String streamSegmentPlaylistUrl) throws IOException, URISyntaxException {
    List<SegmentInfo> segments = new ArrayList<>();
    ExtendedM3uParser.Line segmentInfo = null;

    for (String lineText : fetchResponseLines(httpInterface, new HttpGet(streamSegmentPlaylistUrl), "stream segments list")) {
      ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(lineText);
      if (line.isDirective()) {
        if ("EXTINF".equals(line.directiveName)) {
          segmentInfo = line;
        } else if ("EXT-X-KEY".equals(line.directiveName) && cipher == null) {
          String keyUri = line.directiveArguments.get("URI");
          URI baseUri = new URI(streamSegmentPlaylistUrl);
          URI resolvedUri = baseUri.resolve(keyUri);

          System.out.println("Got Cipher URI: " + resolvedUri);
          initializeCipher(httpInterface, resolvedUri.toString());
        }
      }

      if (line.isData()) {
        if (segmentInfo != null && segmentInfo.extraData.contains(",")) {
          String[] fields = segmentInfo.extraData.split(",", 2);
          segments.add(new SegmentInfo(line.lineData, parseSecondDuration(fields[0]), fields[1]));
        } else {
          segments.add(new SegmentInfo(line.lineData, null, null));
        }
      }
    }

    return segments;
  }

  private static Long parseSecondDuration(String value) {
    try {
      double asDouble = Double.parseDouble(value);
      return (long) (asDouble * 1000.0);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  protected SegmentInfo chooseNextSegment(List<SegmentInfo> segments, SegmentInfo lastSegment) {
    SegmentInfo selected = null;

    for (int i = segments.size() - 1; i >= 0; i--) {
      SegmentInfo current = segments.get(i);
      if (lastSegment != null && current.url.equals(lastSegment.url)) {
        break;
      }

      selected = current;
    }

    return selected;
  }

  private boolean shouldWaitForSegment(long startTime, List<SegmentInfo> segments) {
    if (!segments.isEmpty()) {
      SegmentInfo sampleSegment = segments.get(0);

      if (sampleSegment.duration != null) {
        return System.currentTimeMillis() - startTime < sampleSegment.duration;
      }
    }

    return false;
  }

  protected static class ChannelStreamInfo {
    /**
     * Stream quality extracted from stream M3U directive.
     */
    public final String quality;
    /**
     * URL for stream segment list.
     */
    public final String url;

    private ChannelStreamInfo(String quality, String url) {
      this.quality = quality;
      this.url = url;
    }
  }

  protected static class SegmentInfo {
    /**
     * URL of the segment.
     */
    public final String url;
    /**
     * Duration of the segment in milliseconds. <code>null</code> if unknown.
     */
    public final Long duration;
    /**
     * Name of the segment. <code>null</code> if unknown.
     */
    public final String name;

    public SegmentInfo(String url, Long duration, String name) {
      this.url = url;
      this.duration = duration;
      this.name = name;
    }
  }
}
