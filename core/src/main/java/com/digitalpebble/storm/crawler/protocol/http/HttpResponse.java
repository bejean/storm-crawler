/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.storm.crawler.protocol.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpException;

import backtype.storm.Config;

import com.digitalpebble.storm.crawler.Metadata;
import com.digitalpebble.storm.crawler.protocol.HttpHeaders;
import com.digitalpebble.storm.crawler.util.ConfUtils;

/** An HTTP response. */
public class HttpResponse {

    private Config conf;

    private HttpProtocol http;
    private URL url;
    private byte[] content;
    private int code;
    private final Metadata headers = new Metadata();

    protected enum Scheme {
        HTTP, HTTPS,
    }

    /**
     * Default public constructor.
     *
     * @param http
     * @param url
     * @param knownMetadata
     * @throws IOException
     */
    public HttpResponse(HttpProtocol http, URL url, Metadata knownMetadata)
            throws IOException {

        this.http = http;
        this.url = url;

        Scheme scheme = null;

        if ("http".equals(url.getProtocol())) {
            scheme = Scheme.HTTP;
        } else if ("https".equals(url.getProtocol())) {
            scheme = Scheme.HTTPS;
        } else {
            throw new IOException("Unknown scheme (not http/https) for url:"
                    + url);
        }

        String path = "".equals(url.getFile()) ? "/" : url.getFile();

        // some servers will redirect a request with a host line like
        // "Host: <hostname>:80" to "http://<hpstname>/<orig_path>"- they
        // don't want the :80...

        String host = url.getHost();
        int port;
        String portString;
        if (url.getPort() == -1) {
            if (scheme == Scheme.HTTP) {
                port = 80;
            } else {
                port = 443;
            }
            portString = "";
        } else {
            port = url.getPort();
            portString = ":" + port;
        }
        Socket socket = null;

        try {
            socket = new Socket(); // create the socket
            socket.setSoTimeout(http.getTimeout());

            // connect
            String sockHost = http.useProxy() ? http.getProxyHost() : host;
            int sockPort = http.useProxy() ? http.getProxyPort() : port;
            InetSocketAddress sockAddr = new InetSocketAddress(sockHost,
                    sockPort);
            socket.connect(sockAddr, http.getTimeout());

            if (scheme == Scheme.HTTPS) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory
                        .getDefault();
                SSLSocket sslsocket = (SSLSocket) factory.createSocket(socket,
                        sockHost, sockPort, true);
                sslsocket.setUseClientMode(true);

                // Get the protocols and ciphers supported by this JVM
                Set<String> protocols = new HashSet<String>(
                        Arrays.asList(sslsocket.getSupportedProtocols()));
                Set<String> ciphers = new HashSet<String>(
                        Arrays.asList(sslsocket.getSupportedCipherSuites()));

                // Intersect with preferred protocols and ciphers
                protocols.retainAll(http.getTlsPreferredProtocols());
                ciphers.retainAll(http.getTlsPreferredCipherSuites());

                sslsocket.setEnabledProtocols(protocols
                        .toArray(new String[protocols.size()]));
                sslsocket.setEnabledCipherSuites(ciphers
                        .toArray(new String[ciphers.size()]));

                sslsocket.startHandshake();
                socket = sslsocket;
            }

            this.conf = http.getConf();
            if (ConfUtils.getBoolean(conf, "store.ip.address", false) == true) {
                headers.setValue("_ip_", sockAddr.getAddress().getHostAddress());
            }

            // make request
            OutputStream req = socket.getOutputStream();

            StringBuffer reqStr = new StringBuffer("GET ");
            if (http.useProxy()) {
                reqStr.append(url.getProtocol() + "://" + host + portString
                        + path);
            } else {
                reqStr.append(path);
            }

            reqStr.append(" HTTP/1.0\r\n");

            reqStr.append("Host: ");
            reqStr.append(host);
            reqStr.append(portString);
            reqStr.append("\r\n");

            reqStr.append("Accept-Encoding: x-gzip, gzip, deflate\r\n");

            String userAgent = http.getUserAgent();
            if ((userAgent == null) || (userAgent.length() == 0)) {
                if (HttpProtocol.LOGGER.isErrorEnabled()) {
                    HttpProtocol.LOGGER.error("User-agent is not set!");
                }
            } else {
                reqStr.append("User-Agent: ");
                reqStr.append(userAgent);
                reqStr.append("\r\n");
            }

            reqStr.append("Accept-Language: ");
            reqStr.append(this.http.getAcceptLanguage());
            reqStr.append("\r\n");

            reqStr.append("Accept: ");
            reqStr.append(this.http.getAccept());
            reqStr.append("\r\n");

            if (knownMetadata != null) {
                String ifModifiedSince = knownMetadata
                        .getFirstValue("cachedLastModified");
                if (StringUtils.isNotBlank(ifModifiedSince)) {
                    reqStr.append("If-Modified-Since: ");
                    reqStr.append(ifModifiedSince);
                    reqStr.append("\r\n");
                }

                String ifNoneMatch = knownMetadata.getFirstValue("cachedEtag");
                if (StringUtils.isNotBlank(ifNoneMatch)) {
                    reqStr.append("If-None-Match: ");
                    reqStr.append(ifNoneMatch);
                    reqStr.append("\r\n");
                }
            }

            reqStr.append("\r\n");

            // @see http://www.w3.org/Protocols/rfc2068/rfc2068.txt for default
            // charset
            // TODO use UTF-8 and set a charset value explicitely
            byte[] reqBytes = reqStr.toString().getBytes(
                    StandardCharsets.ISO_8859_1);

            req.write(reqBytes);
            req.flush();

            PushbackInputStream in = // process response
            new PushbackInputStream(new BufferedInputStream(
                    socket.getInputStream(), HttpProtocol.BUFFER_SIZE),
                    HttpProtocol.BUFFER_SIZE);

            StringBuffer line = new StringBuffer();

            try {
                boolean haveSeenNonContinueStatus = false;
                while (!haveSeenNonContinueStatus) {
                    // parse status code line
                    this.code = parseStatusLine(in, line);
                    // parse headers
                    parseHeaders(in, line);
                    haveSeenNonContinueStatus = code != 100; // 100 is
                                                             // "Continue"
                }
                String transferEncoding = getHeader(HttpHeaders.TRANSFER_ENCODING);
                if (transferEncoding != null
                        && "chunked".equalsIgnoreCase(transferEncoding.trim())) {
                    readChunkedContent(in, line);
                } else {
                    readPlainContent(in);
                }

                String contentEncoding = getHeader(HttpHeaders.CONTENT_ENCODING);
                if ("gzip".equals(contentEncoding)
                        || "x-gzip".equals(contentEncoding)) {
                    content = http.processGzipEncoded(content, url);
                } else if ("deflate".equals(contentEncoding)) {
                    content = http.processDeflateEncoded(content, url);
                } else {
                    HttpProtocol.LOGGER.trace("fetched {}  bytes from {}",
                            content.length, url);
                }
            } catch (Exception e) {
                HttpProtocol.LOGGER.debug(
                        "Exception while reading content on {}", url, e);
            }
        } finally {
            if (socket != null)
                socket.close();
        }

    }

    /*
     * ------------------------- * <implementation:Response> *
     * -------------------------
     */

    public URL getUrl() {
        return url;
    }

    public int getCode() {
        return code;
    }

    public String getHeader(String name) {
        return headers.getFirstValue(name);
    }

    public Metadata getHeaders() {
        return headers;
    }

    public byte[] getContent() {
        return content;
    }

    /*
     * ------------------------- * <implementation:Response> *
     * -------------------------
     */

    private void readPlainContent(InputStream in) throws HttpException,
            IOException {

        int contentLength = Integer.MAX_VALUE; // get content length
        String contentLengthString = getHeader(HttpHeaders.CONTENT_LENGTH);
        if (contentLengthString != null) {
            contentLengthString = contentLengthString.trim();
            try {
                if (!contentLengthString.isEmpty())
                    contentLength = Integer.parseInt(contentLengthString);
            } catch (NumberFormatException e) {
                throw new HttpException("bad content length: "
                        + contentLengthString);
            }
        }
        // limit download size
        if (http.getMaxContent() >= 0 && contentLength > http.getMaxContent())
            contentLength = http.getMaxContent();

        // don't even try to read
        if (contentLength == 0) {
            content = new byte[0];
            return;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                HttpProtocol.BUFFER_SIZE);
        byte[] bytes = new byte[HttpProtocol.BUFFER_SIZE];
        int length = 0; // read content
        for (int i = in.read(bytes); i != -1 && length + i <= contentLength; i = in
                .read(bytes)) {
            out.write(bytes, 0, i);
            length += i;
        }
        content = out.toByteArray();
    }

    private void readChunkedContent(PushbackInputStream in, StringBuffer line)
            throws HttpException, IOException {
        boolean doneChunks = false;
        int contentBytesRead = 0;
        byte[] bytes = new byte[HttpProtocol.BUFFER_SIZE];
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                HttpProtocol.BUFFER_SIZE);

        while (!doneChunks) {
            if (HttpProtocol.LOGGER.isTraceEnabled()) {
                HttpProtocol.LOGGER.trace("Http: starting chunk");
            }

            readLine(in, line, false);

            String chunkLenStr;
            // if (LOG.isTraceEnabled()) { LOG.trace("chunk-header: '" + line +
            // "'"); }

            int pos = line.indexOf(";");
            if (pos < 0) {
                chunkLenStr = line.toString();
            } else {
                chunkLenStr = line.substring(0, pos);
                // if (LOG.isTraceEnabled()) { LOG.trace("got chunk-ext: " +
                // line.substring(pos+1)); }
            }
            chunkLenStr = chunkLenStr.trim();
            int chunkLen;
            try {
                chunkLen = Integer.parseInt(chunkLenStr, 16);
            } catch (NumberFormatException e) {
                throw new HttpException("bad chunk length: " + line.toString());
            }

            if (chunkLen == 0) {
                doneChunks = true;
                break;
            }

            if (http.getMaxContent() >= 0
                    && (contentBytesRead + chunkLen) > http.getMaxContent())
                chunkLen = http.getMaxContent() - contentBytesRead;

            // read one chunk
            int chunkBytesRead = 0;
            while (chunkBytesRead < chunkLen) {

                int toRead = (chunkLen - chunkBytesRead) < HttpProtocol.BUFFER_SIZE ? (chunkLen - chunkBytesRead)
                        : HttpProtocol.BUFFER_SIZE;
                int len = in.read(bytes, 0, toRead);

                if (len == -1)
                    throw new HttpException("chunk eof after "
                            + contentBytesRead + " bytes in successful chunks"
                            + " and " + chunkBytesRead + " in current chunk");

                // DANGER!!! Will printed GZIPed stuff right to your
                // terminal!
                // if (LOG.isTraceEnabled()) { LOG.trace("read: " + new
                // String(bytes, 0, len)); }

                out.write(bytes, 0, len);
                chunkBytesRead += len;
            }

            readLine(in, line, false);

        }

        if (!doneChunks) {
            if (contentBytesRead != http.getMaxContent())
                throw new HttpException(
                        "chunk eof: !doneChunk && didn't max out");
            return;
        }

        content = out.toByteArray();
        parseHeaders(in, line);

    }

    private int parseStatusLine(PushbackInputStream in, StringBuffer line)
            throws IOException, HttpException {
        readLine(in, line, false);

        int codeStart = line.indexOf(" ");
        int codeEnd = line.indexOf(" ", codeStart + 1);

        // handle lines with no plaintext result code, ie:
        // "HTTP/1.1 200" vs "HTTP/1.1 200 OK"
        if (codeEnd == -1)
            codeEnd = line.length();

        int code;
        try {
            code = Integer.parseInt(line.substring(codeStart + 1, codeEnd));
        } catch (NumberFormatException e) {
            throw new HttpException("bad status line '" + line + "': "
                    + e.getMessage(), e);
        }

        return code;
    }

    private void processHeaderLine(StringBuffer line) throws HttpException {

        int colonIndex = line.indexOf(":"); // key is up to colon
        if (colonIndex == -1) {
            int i;
            for (i = 0; i < line.length(); i++)
                if (!Character.isWhitespace(line.charAt(i)))
                    break;
            if (i == line.length())
                return;
            throw new HttpException("No colon in header:" + line);
        }
        String key = line.substring(0, colonIndex);

        int valueStart = colonIndex + 1; // skip whitespace
        while (valueStart < line.length()) {
            int c = line.charAt(valueStart);
            if (c != ' ' && c != '\t')
                break;
            valueStart++;
        }
        String value = line.substring(valueStart);
        headers.addValue(key.toLowerCase(Locale.ROOT), value);
    }

    // Adds headers to our headers Metadata
    private void parseHeaders(PushbackInputStream in, StringBuffer line)
            throws IOException, HttpException {

        while (readLine(in, line, true) != 0) {

            // handle HTTP responses with missing blank line after headers
            int pos;
            if (((pos = line.indexOf("<!DOCTYPE")) != -1)
                    || ((pos = line.indexOf("<HTML")) != -1)
                    || ((pos = line.indexOf("<html")) != -1)) {

                in.unread(line.substring(pos).getBytes(StandardCharsets.UTF_8));
                line.setLength(pos);

                try {
                    // TODO: (CM) We don't know the header names here
                    // since we're just handling them generically. It would
                    // be nice to provide some sort of mapping function here
                    // for the returned header names to the standard metadata
                    // names in the ParseData class
                    processHeaderLine(line);
                } catch (Exception e) {
                    // fixme:
                    HttpProtocol.LOGGER.warn("Error: ", e);
                }
                return;
            }

            processHeaderLine(line);
        }
    }

    private static int readLine(PushbackInputStream in, StringBuffer line,
            boolean allowContinuedLine) throws IOException {
        line.setLength(0);
        for (int c = in.read(); c != -1; c = in.read()) {
            switch (c) {
            case '\r':
                if (peek(in) == '\n') {
                    in.read();
                }
            case '\n':
                if (line.length() > 0) {
                    // at EOL -- check for continued line if the current
                    // (possibly continued) line wasn't blank
                    if (allowContinuedLine)
                        switch (peek(in)) {
                        case ' ':
                        case '\t': // line is continued
                            in.read();
                            continue;
                        }
                }
                return line.length(); // else complete
            default:
                line.append((char) c);
            }
        }
        throw new EOFException();
    }

    private static int peek(PushbackInputStream in) throws IOException {
        int value = in.read();
        in.unread(value);
        return value;
    }

}
