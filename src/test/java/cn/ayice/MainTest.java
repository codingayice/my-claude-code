package cn.ayice;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void acceptsUtf8RuntimeCharset() {
        assertTrue(Main.isUtf8Runtime(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNonUtf8RuntimeCharset() {
        assertFalse(Main.isUtf8Runtime(Charset.forName("GBK")));
    }
}
