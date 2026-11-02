package com.nfv.auth.cimd;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches metadata documents over HTTPS with a minimal HTTP/1.1 client. A plain HTTP client would resolve the host
 * itself after our check (DNS rebinding); here the connection goes to exactly the address that was checked by
 * {@link AddressPolicy}, while TLS still verifies the certificate for the host name. No redirects, no proxy, limited
 * time and size.
 */
public class HttpsClientMetadataSource implements ClientMetadataSource {
    private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");

    private final Duration timeout;
    private final int maxSize;

    public HttpsClientMetadataSource(Duration timeout, int maxSize) {
        this.timeout = timeout;
        this.maxSize = maxSize;
    }

    @Override
    public Document fetch(URI clientId) {
        String host = clientId.getHost();
        InetAddress address = AddressPolicy.resolvePublic(host);
        long deadline = System.nanoTime() + timeout.toNanos();
        int timeoutMillis = (int) timeout.toMillis();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, 443), timeoutMillis);
            raw.setSoTimeout(timeoutMillis);
            try (SSLSocket socket = (SSLSocket) SSLContext.getDefault().getSocketFactory()
                    .createSocket(raw, host, 443, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                writeRequest(socket.getOutputStream(), host, clientId.getRawPath());
                return readResponse(socket.getInputStream(), deadline);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new CimdException("fetching " + clientId + " failed: " + e.getMessage(), e);
        }
    }

    private static void writeRequest(OutputStream out, String host, String path) throws IOException {
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Accept: application/json\r\n"
                + "Accept-Encoding: identity\r\n"
                + "User-Agent: Nfv-IdP\r\n"
                + "Connection: close\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private Document readResponse(InputStream in, long deadline) throws IOException {
        LimitedReader reader = new LimitedReader(in, deadline, maxSize + 16 * 1024);
        String statusLine = reader.line();
        String[] status = statusLine.split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) {
            throw new CimdException("invalid HTTP response");
        }
        if (!status[1].equals("200")) {
            throw new CimdException("HTTP status " + status[1] + " (redirects are not followed)");
        }
        Map<String, String> headers = new HashMap<>();
        for (String line = reader.line(); !line.isEmpty(); line = reader.line()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        String contentType = headers.getOrDefault("content-type", "");
        if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
            throw new CimdException("content type " + contentType + " is not JSON");
        }
        byte[] body;
        if (headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) {
            body = reader.chunked(maxSize);
        } else if (headers.containsKey("content-length")) {
            long length = Long.parseLong(headers.get("content-length"));
            if (length > maxSize) {
                throw new CimdException("document larger than " + maxSize + " bytes");
            }
            body = reader.bytes((int) length);
        } else {
            body = reader.untilEnd(maxSize);
        }
        return new Document(new String(body, StandardCharsets.UTF_8), maxAge(headers.get("cache-control")));
    }

    static Duration maxAge(String cacheControl) {
        if (cacheControl == null) {
            return null;
        }
        String value = cacheControl.toLowerCase(Locale.ROOT);
        if (value.contains("no-store") || value.contains("no-cache")) {
            return Duration.ZERO;
        }
        Matcher matcher = MAX_AGE.matcher(value);
        return matcher.find() ? Duration.ofSeconds(Long.parseLong(matcher.group(1))) : null;
    }

    /**
     * Reads with an overall deadline and size limit.
     */
    private static final class LimitedReader {
        private final InputStream in;
        private final long deadline;
        private final int limit;
        private int total;

        LimitedReader(InputStream in, long deadline, int limit) {
            this.in = in;
            this.deadline = deadline;
            this.limit = limit;
        }

        int read() throws IOException {
            if (System.nanoTime() > deadline) {
                throw new CimdException("timeout");
            }
            if (++total > limit) {
                throw new CimdException("response too large");
            }
            return in.read();
        }

        String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = read(); b != '\n'; b = read()) {
                if (b == -1) {
                    throw new CimdException("unexpected end of response");
                }
                if (b != '\r') {
                    line.write(b);
                }
            }
            return line.toString(StandardCharsets.ISO_8859_1);
        }

        byte[] bytes(int length) throws IOException {
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                int b = read();
                if (b == -1) {
                    throw new CimdException("unexpected end of response");
                }
                result[i] = (byte) b;
            }
            return result;
        }

        byte[] untilEnd(int maxSize) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (int b = read(); b != -1; b = read()) {
                if (body.size() >= maxSize) {
                    throw new CimdException("document larger than " + maxSize + " bytes");
                }
                body.write(b);
            }
            return body.toByteArray();
        }

        byte[] chunked(int maxSize) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = line();
                int semicolon = sizeLine.indexOf(';');
                int size = Integer.parseInt((semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim(), 16);
                if (size == 0) {
                    return body.toByteArray();
                }
                if (body.size() + size > maxSize) {
                    throw new CimdException("document larger than " + maxSize + " bytes");
                }
                body.write(bytes(size));
                line();
            }
        }
    }
}
