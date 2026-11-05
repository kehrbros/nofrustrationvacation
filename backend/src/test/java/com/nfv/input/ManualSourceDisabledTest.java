package com.nfv.input;

import com.nfv.input.api.TestTrafficEventController;
import com.nfv.input.sources.manual.ManualSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Without the setting neither the source nor the endpoints exist. */
@SpringBootTest
class ManualSourceDisabledTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void offByDefault() {
        assertThat(context.getBeansOfType(ManualSource.class)).isEmpty();
        assertThat(context.getBeansOfType(TestTrafficEventController.class)).isEmpty();
    }
}
