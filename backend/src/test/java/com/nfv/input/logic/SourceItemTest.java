package com.nfv.input.logic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** message_hash: SHA-256 over the text with whitespace collapsed, case kept, none without text. */
class SourceItemTest {
    @Test
    void whitespaceDoesNotChangeTheHash() {
        String hash = SourceItem.hash("SH 1 Terrace Tunnel, northbound\nClosed at night.");
        assertThat(hash).hasSize(64).isEqualTo(SourceItem.hash("  SH 1  Terrace Tunnel, northbound Closed\tat night. "));
    }

    @Test
    void caseAndWordsChangeTheHash() {
        String hash = SourceItem.hash("Closed between 9pm and 4:30am");
        assertThat(SourceItem.hash("closed between 9pm and 4:30am")).isNotEqualTo(hash);
        assertThat(SourceItem.hash("Closed between 10pm and 5am")).isNotEqualTo(hash);
    }

    @Test
    void noHashWithoutText() {
        assertThat(SourceItem.hash(null)).isNull();
        assertThat(SourceItem.hash(" \n ")).isNull();
        assertThat(new SourceItem("1", "v1", "{}", null).messageHash()).isNull();
    }
}
