package com.nfv.input.sources.nz;

import com.nfv.input.config.InputProperties;
import com.nfv.input.logic.FetchContext;
import com.nfv.input.logic.PollPolicy;
import com.nfv.input.logic.SourceItem;
import com.nfv.input.logic.SourceSnapshot;
import com.nfv.input.logic.SourceUnavailableException;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** NztaSource.fetch against the sample response: from classpath, file and a mocked HTTP server. */
class NztaSourceTest {
    private static final String API = "https://trafficnz.info/service/traffic/rest/4/events/all/10";
    private static final String FIXTURES = "classpath:sources/nz-nzta/";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-03T22:15:00+13:00");

    private MockRestServiceServer server;
    private NztaSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new NztaSource(properties(FIXTURES + "events.json"), new DefaultResourceLoader(),
                "https://app.example", builder.build());
    }

    @Test
    void readsAllEventsOfTheSample() {
        SourceSnapshot snapshot = source.fetch(context(null, null, null));

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.notModified()).isFalse();
        assertThat(snapshot.etag()).isNull();
        assertThat(snapshot.items()).hasSize(197);
        assertThat(snapshot.items()).extracting(SourceItem::subjectId).doesNotHaveDuplicates();
        // alternativeRoute placeholders ("Not Applicable", "Not applicable. ", "N/A") are left out
        assertThat(snapshot.items()).allSatisfy(item -> assertThat(item.messageText()).isNotBlank()
                .doesNotEndWithIgnoringCase("not applicable").doesNotEndWithIgnoringCase("not applicable.")
                .doesNotEndWith("N/A"));
        assertThat(item(snapshot, "565256").messageText()).doesNotContainIgnoringCase("not applicable");

        SourceItem tunnel = item(snapshot, "566003");
        assertThat(tunnel.version()).isEqualTo("2026-11-03T21:00:06.200+13:00");
        assertThat(tunnel.messageText()).startsWith("SH 1 Terrace Tunnel, northbound\nDue to improvements project")
                .endsWith("short notice.\nNorthbound traffic, detour via local roads and Aotea Quay On-ramp");
        assertThat(JsonMapper.builder().build().readTree(tunnel.payload()).get("locations").asString())
                .isEqualTo("01N-1068/05.95-B   TERRACE TUNNEL START TO TERRACE TUNNEL END");
    }

    @Test
    void derivedFilesChangeOnlyWhatTheirNameSays() {
        SourceItem original = item(source.fetch(context(null, null, null)), "566003");
        SourceItem text = item(source.fetch(context(null, null, FIXTURES + "events-changed-text.json")), "566003");
        SourceItem status = item(source.fetch(context(null, null, FIXTURES + "events-changed-status.json")), "566003");

        assertThat(text.version()).isNotEqualTo(original.version());
        assertThat(text.messageHash()).isNotEqualTo(original.messageHash());
        assertThat(status.version()).isNotEqualTo(original.version());
        assertThat(status.messageHash()).isEqualTo(original.messageHash());
        assertThat(source.fetch(context(null, null, FIXTURES + "events-cleared.json")).items())
                .hasSize(196).noneMatch(item -> item.subjectId().equals("566003"));
    }

    @Test
    void emptyResponseMeansNoEvents() {
        SourceSnapshot snapshot = source.fetch(context(null, null, FIXTURES + "events-empty.json"));

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.items()).isEmpty();
    }

    @Test
    void readsFiles(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("events.json");
        Files.write(file, new ClassPathResource("sources/nz-nzta/events.json").getContentAsByteArray());

        assertThat(source.fetch(context(null, null, file.toUri().toString())).items()).hasSize(197);
    }

    @Test
    void missingFileIsUnavailable() {
        assertThatThrownBy(() -> source.fetch(context(null, null, FIXTURES + "missing.json")))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    void fetchesJsonOverHttpWithValidators() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"v2\"");
        headers.set(HttpHeaders.LAST_MODIFIED, "Tue, 03 Nov 2026 09:13:00 GMT");
        server.expect(requestTo(API))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.USER_AGENT, "Nfv (+https://app.example)"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"v1\""))
                .andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, "Mon, 02 Nov 2026 09:00:00 GMT"))
                .andRespond(withSuccess(fixture("events.json"), MediaType.APPLICATION_JSON).headers(headers));

        SourceSnapshot snapshot = source.fetch(context("\"v1\"", "Mon, 02 Nov 2026 09:00:00 GMT", API));

        assertThat(snapshot.items()).hasSize(197);
        assertThat(snapshot.etag()).isEqualTo("\"v2\"");
        assertThat(snapshot.lastModified()).isEqualTo("Tue, 03 Nov 2026 09:13:00 GMT");
        server.verify();
    }

    @Test
    void firstRequestHasNoValidators() throws IOException {
        server.expect(requestTo(API))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess(fixture("events-empty.json"), MediaType.APPLICATION_JSON));

        assertThat(source.fetch(context(null, null, API)).items()).isEmpty();
        server.verify();
    }

    @Test
    void notModifiedOverHttp() {
        server.expect(requestTo(API)).andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        SourceSnapshot snapshot = source.fetch(context("\"v1\"", null, API));

        assertThat(snapshot.notModified()).isTrue();
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void errorsAreUnavailable() {
        server.expect(requestTo(API)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(API)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(API)).andRespond(withSuccess("{\"response\": {\"roadevent\": [", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API)).andRespond(withSuccess("{\"error\": \"x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> source.fetch(context(null, null, API)))
                    .isInstanceOf(SourceUnavailableException.class);
        }
        server.verify();
    }

    @Test
    void configurationOverridesDefaults() {
        assertThat(source.name()).isEqualTo("nz-nzta");
        assertThat(source.country()).isEqualTo("NZ");
        assertThat(source.networkRegion()).isEqualTo("wellington");
        PollPolicy policy = source.pollPolicy();
        assertThat(policy.interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(policy.clearAfterMissing()).isEqualTo(2);
        assertThat(policy.maxClearRatio()).isEqualTo(0.5);

        NztaSource unconfigured = new NztaSource(new InputProperties(null, null, null, null), new DefaultResourceLoader(),
                "https://app.example");
        assertThat(unconfigured.networkRegion()).isEqualTo("nz");
        assertThat(unconfigured.pollPolicy().timeout()).isEqualTo(Duration.ofSeconds(20));
    }

    static InputProperties properties(String url) {
        return new InputProperties(null, null, null, Map.of(NztaSource.NAME, new InputProperties.Source(true, url,
                "wellington", null, null, null, Duration.ofSeconds(7), Map.of("delays", Duration.ofMinutes(20)))));
    }

    private static FetchContext context(@Nullable String etag, @Nullable String lastModified,
                                        @Nullable String location) {
        return new FetchContext(etag, lastModified, NOW, location);
    }

    private static SourceItem item(SourceSnapshot snapshot, String subjectId) {
        return snapshot.items().stream().filter(item -> item.subjectId().equals(subjectId)).findFirst().orElseThrow();
    }

    private static byte[] fixture(String name) throws IOException {
        return new ClassPathResource("sources/nz-nzta/" + name).getContentAsByteArray();
    }
}
