
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

// https://stackoverflow.com/a/26226246/1778299
@Log4j2
@Component
public class GzipBodyDecompressFilter implements Filter {

  @Override public void init(FilterConfig filterConfig) {
    //Nothing to do
  }

  /**
   * Analyzes servlet request for possible gzipped body.
   * When Content-Encoding header has "gzip" value and request method is POST we read all the
   * gzipped stream and is it haz any data unzip it. In case when gzip Content-Encoding header
   * specified but body is not actually in gzip format we will throw ZipException.
   *
   * @param servletRequest  servlet request
   * @param servletResponse servlet response
   * @param chain           filter chain
   * @throws IOException      throws when fails
   * @throws ServletException thrown when fails
   */
  @Override public final void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse, final FilterChain chain)
    throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    HttpServletResponse response = (HttpServletResponse) servletResponse;
    boolean isGzipped = request.getHeader(HttpHeaders.CONTENT_ENCODING) != null && request.getHeader(HttpHeaders.CONTENT_ENCODING).contains("gzip");
    boolean requestTypeSupported = "POST".equals(request.getMethod());
    if (isGzipped && !requestTypeSupported) {
      throw new IllegalStateException(request.getMethod() + " is not supports gzipped body of parameters." + " Only POST requests are currently supported.");
    }
    String requestURI = ((HttpServletRequest) servletRequest).getRequestURI();
    if (isGzipped) {
      log.debug("Decompressing POST request to {}", requestURI);
      Stopwatch timer = Stopwatch.createStarted();
      request = new GzippedInputStreamWrapper((HttpServletRequest) servletRequest);
      log.debug("POST request to {} decompressed successfully in {}", requestURI, timer);
    }
    else if(requestTypeSupported){
      log.debug("POST body to {} does not require decompression. Skipping filter", requestURI);
    }
    chain.doFilter(request, response);

  }

  /**
   * @inheritDoc
   */
  @Override public final void destroy() {
    //Nothing to destroy
  }

  /**
   * Wrapper class that detects if the request is gzipped and ungzipps it.
   */
  static final class GzippedInputStreamWrapper extends HttpServletRequestWrapper {
    /**
     * Default encoding that is used when post parameters are parsed.
     */
    static final String DEFAULT_ENCODING = "ISO-8859-1";

    /**
     * Serialized bytes array that is a result of unzipping gzipped body.
     */
    private byte[] bytes;

    /**
     * Constructs a request object wrapping the given request.
     * In case if Content-Encoding contains "gzip" we wrap the input stream into byte array
     * to original input stream has nothing in it but hew wrapped input stream always returns
     * reproducible ungzipped input stream.
     *
     * @param request request which input stream will be wrapped.
     * @throws java.io.IOException when input stream reqtieval failed.
     */
    GzippedInputStreamWrapper(final HttpServletRequest request) throws IOException {
      super(request);
      try {
        final InputStream in = new GZIPInputStream(request.getInputStream());
        bytes = ByteStreams.toByteArray(in);
      } catch (EOFException e) {
        bytes = new byte[0];
      }
    }


    /**
     * @return reproduceable input stream that is either equal to initial servlet input
     * stream(if it was not zipped) or returns unzipped input stream.
     */
    @Override public ServletInputStream getInputStream() {
      final ByteArrayInputStream sourceStream = new ByteArrayInputStream(bytes);
      return new ServletInputStream() {
        private ReadListener readListener;

        @Override public boolean isFinished() {
          return sourceStream.available() <= 0;
        }

        @Override public boolean isReady() {
          return sourceStream.available() > 0;
        }

        @Override public void setReadListener(ReadListener readListener) {
          this.readListener = readListener;
        }

        public ReadListener getReadListener() {
          return readListener;
        }

        public int read() {
          return sourceStream.read();
        }

        @Override
        public void close() throws IOException {
          super.close();
          sourceStream.close();
        }
      };
    }

    /**
     * Need to override getParametersMap because we initially read the whole input stream and
     * servlet container won't have access to the input stream data.
     *
     * @return parsed parameters list. Parameters get parsed only when Content-Type
     * "application/x-www-form-urlencoded" is set.
     */
    @Override public Map<String, String[]> getParameterMap() {
      String contentEncodingHeader = getHeader(HttpHeaders.CONTENT_TYPE);
      if (!Strings.isNullOrEmpty(contentEncodingHeader) && contentEncodingHeader.contains("application/x-www-form-urlencoded")) {
        Map<String, String[]> params = new HashMap<>(super.getParameterMap());
        try {
          params.putAll(parseParams(new String(bytes)));
        } catch (UnsupportedEncodingException e) {
          log.error("Could not decompress incoming message!", e);
        }
        return params;
      } else {
        return super.getParameterMap();
      }
    }

    /**
     * parses params from the byte input stream.
     *
     * @param body request body serialized to string.
     * @return parsed parameters map.
     * @throws UnsupportedEncodingException if encoding provided is not supported.
     */
    private Map<String, String[]> parseParams(final String body) throws UnsupportedEncodingException {
      String characterEncoding = getCharacterEncoding();
      if (null == characterEncoding) {
        characterEncoding = DEFAULT_ENCODING;
      }
      final Multimap<String, String> parameters = ArrayListMultimap.create();
      for (String pair : body.split("&")) {
        if (Strings.isNullOrEmpty(pair)) {
          continue;
        }
        int idx = pair.indexOf('=');

        String key;
        if (idx > 0) {
          key = URLDecoder.decode(pair.substring(0, idx), characterEncoding);
        } else {
          key = pair;
        }
        String value;
        if (idx > 0 && pair.length() > idx + 1) {
          value = URLDecoder.decode(pair.substring(idx + 1), characterEncoding);
        } else {
          value = null;
        }
        parameters.put(key, value);
      }

      return parameters.asMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, kv -> Iterables.toArray(kv.getValue(), String.class)));
    }
  }
}
