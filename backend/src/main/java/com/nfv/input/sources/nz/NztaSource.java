package com.nfv.input.sources.nz;

import com.nfv.input.config.InputProperties;
import com.nfv.input.entities.EventSourceType;
import com.nfv.input.logic.Attribution;
import com.nfv.input.logic.FetchContext;
import com.nfv.input.logic.PollPolicy;
import com.nfv.input.logic.SourceItem;
import com.nfv.input.logic.SourceSnapshot;
import com.nfv.input.logic.SourceUnavailableException;
import com.nfv.input.logic.TrafficReport;
import com.nfv.input.logic.TrafficSource;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * NZTA Traffic and Travel REST API v4 (State Highways of New Zealand), see Docs/traffic-feed.md#erste-quelle-nzta.
 * The configured {@code url} is either the API ({@code http(s):}) or a JSON file with a response ({@code classpath:},
 * {@code file:}, for tests and development); both are parsed the same way.
 */
@Component
public class NztaSource implements TrafficSource {
    public static final String NAME = "nz-nzta";
    static final String DEFAULT_URL = "https://trafficnz.info/service/traffic/rest/4/events/all/10";

    private static final Logger LOG = LoggerFactory.getLogger(NztaSource.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final PollPolicy DEFAULTS = new PollPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30),
            Duration.ofMinutes(30), Duration.ofSeconds(20), 2, 0.5, 10);
    private static final Attribution ATTRIBUTION = new Attribution("NZ Transport Agency Waka Kotahi",
            "Nur bestätigte, bedeutende Ereignisse auf State Highways.", Duration.ofMinutes(30));

    private final String url;
    private final String networkRegion;
    private final PollPolicy pollPolicy;
    private final String userAgent;
    private final ResourceLoader resourceLoader;
    private final RestClient restClient;
    private final NztaReader reader;

    @Autowired
    public NztaSource(InputProperties properties, ResourceLoader resourceLoader,
                      @Value("${nfv.frontend-url}") String appUrl) {
        this(properties, resourceLoader, appUrl, null);
    }

    /** @param restClient client for {@code http(s)} (tests: bound to a mock server), {@code null} to create one */
    NztaSource(InputProperties properties, ResourceLoader resourceLoader, String appUrl,
               @Nullable RestClient restClient) {
        InputProperties.Source config = properties.source(NAME);
        this.url = config != null && config.url() != null ? config.url() : DEFAULT_URL;
        this.networkRegion = config != null && config.networkRegion() != null ? config.networkRegion() : "nz";
        this.pollPolicy = config == null ? DEFAULTS : new PollPolicy(
                Objects.requireNonNullElse(config.interval(), DEFAULTS.interval()),
                Objects.requireNonNullElse(config.jitter(), DEFAULTS.jitter()),
                Objects.requireNonNullElse(config.maxBackoff(), DEFAULTS.maxBackoff()),
                Objects.requireNonNullElse(config.timeout(), DEFAULTS.timeout()),
                DEFAULTS.clearAfterMissing(), DEFAULTS.maxClearRatio(), DEFAULTS.minKnownForRatio());
        this.userAgent = "Nfv (+" + appUrl + ")";
        this.resourceLoader = resourceLoader;
        if (restClient == null) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(pollPolicy.timeout());
            requestFactory.setReadTimeout(pollPolicy.timeout());
            restClient = RestClient.builder().requestFactory(requestFactory).build();
        }
        this.restClient = restClient;
        this.reader = new NztaReader(config == null ? Map.of() : config.assumedDelay());
    }

    /** Field table and type rules in Docs/traffic-feed.md#erste-quelle-nzta, see {@link NztaReader}. */
    @Override
    public TrafficReport read(String payload) {
        return reader.read(payload);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String country() {
        return "NZ";
    }

    @Override
    public String networkRegion() {
        return networkRegion;
    }

    @Override
    public ZoneId zone() {
        return NztaReader.ZONE;
    }

    @Override
    public EventSourceType type() {
        return EventSourceType.TRAFFIC_FEED;
    }

    @Override
    public PollPolicy pollPolicy() {
        return pollPolicy;
    }

    @Override
    public Attribution attribution() {
        return ATTRIBUTION;
    }

    @Override
    public SourceSnapshot fetch(FetchContext context) {
        String location = context.location() != null ? context.location() : url;
        String scheme = location.substring(0, Math.max(location.indexOf(':'), 0)).toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https") ? fetchHttp(location, context) : fetchResource(location);
    }

    private SourceSnapshot fetchHttp(String location, FetchContext context) {
        try {
            return restClient.get()
                    .uri(URI.create(location))
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .headers(headers -> {
                        if (context.etag() != null) {
                            headers.setIfNoneMatch(context.etag());
                        }
                        if (context.lastModified() != null) {
                            headers.set(HttpHeaders.IF_MODIFIED_SINCE, context.lastModified());
                        }
                    })
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                            return SourceSnapshot.unchanged();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new SourceUnavailableException(NAME + " answered HTTP "
                                    + response.getStatusCode().value());
                        }
                        byte[] body = response.getBody().readAllBytes();
                        return SourceSnapshot.complete(parse(body), response.getHeaders().getETag(),
                                response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED));
                    });
        } catch (RestClientException e) {
            throw new SourceUnavailableException(NAME + " not reachable: " + e.getMessage(), e);
        }
    }

    private SourceSnapshot fetchResource(String location) {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return SourceSnapshot.complete(parse(in.readAllBytes()), null, null);
        } catch (IOException e) {
            throw new SourceUnavailableException("Cannot read " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * Splits a response ({@code {"response": {"roadevent": [...]}}}) into its events. {@code "response": ""} means no
     * events. Events without {@code id} or modification time are skipped.
     */
    static List<SourceItem> parse(byte[] json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new SourceUnavailableException(NAME + " sent unreadable JSON: " + e.getOriginalMessage(), e);
        }
        JsonNode response = root == null ? null : root.get("response");
        if (response == null) {
            throw new SourceUnavailableException(NAME + " sent no \"response\"");
        }
        if (response.isString() && response.asString().isBlank()) {
            return List.of();
        }
        JsonNode events = response.get("roadevent");
        if (events == null || !(events.isArray() || events.isObject())) {
            throw new SourceUnavailableException(NAME + " sent no \"roadevent\" list");
        }
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode event : events.isArray() ? events.values() : List.of(events)) {
            String id = scalar(event.get("id"));
            String version = NztaReader.text(event, "eventModified");
            if (version == null) {
                version = NztaReader.text(event, "eventCreated");
            }
            if (id == null || version == null) {
                LOG.warn("{}: skipping event without id or modification time: {}", NAME, id);
                continue;
            }
            items.add(new SourceItem(id, version, MAPPER.writeValueAsString(event), NztaReader.messageText(event)));
        }
        return items;
    }

    private static @Nullable String scalar(@Nullable JsonNode node) {
        if (node == null || !(node.isString() || node.isNumber())) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
