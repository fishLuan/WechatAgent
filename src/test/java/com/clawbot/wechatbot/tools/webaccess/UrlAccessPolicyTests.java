package com.clawbot.wechatbot.tools.webaccess;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlAccessPolicyTests {
    @Test
    void acceptsAHostnameThatResolvesOnlyToPublicAddresses() throws Exception {
        UrlAccessPolicy policy = policyWith("8.8.8.8", "1.1.1.1");

        assertDoesNotThrow(() -> policy.validate(URI.create("https://example.com/page")));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndMetadataAddresses() throws Exception {
        assertRejected("127.0.0.1");
        assertRejected("10.0.0.1");
        assertRejected("172.20.1.1");
        assertRejected("192.168.1.1");
        assertRejected("169.254.169.254");
        assertRejected("100.64.0.1");
        assertRejected("fc00::1");
        assertRejected("fe80::1");
    }

    @Test
    void rejectsTheWholeHostnameWhenAnyResolvedAddressIsPrivate() throws Exception {
        UrlAccessPolicy policy = policyWith("8.8.8.8", "10.1.2.3");

        assertThrows(
            UnsafeUrlException.class,
            () -> policy.validate(URI.create("https://mixed.example/path"))
        );
    }

    @Test
    void rejectsLocalNamesCredentialsAndUnexpectedPorts() throws Exception {
        UrlAccessPolicy publicPolicy = policyWith("8.8.8.8");

        assertThrows(
            UnsafeUrlException.class,
            () -> publicPolicy.validate(URI.create("http://localhost/test")));
        assertThrows(
            UnsafeUrlException.class,
            () -> publicPolicy.validate(URI.create("https://user:pass@example.com")));
        assertThrows(
            UnsafeUrlException.class,
            () -> publicPolicy.validate(URI.create("https://example.com:8080")));
    }

    private void assertRejected(String address) throws Exception {
        UrlAccessPolicy policy = policyWith(address);
        assertThrows(
            UnsafeUrlException.class,
            () -> policy.validate(URI.create("https://example.com")));
    }

    private UrlAccessPolicy policyWith(String... addresses) throws Exception {
        InetAddress[] resolved = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            resolved[i] = InetAddress.getByName(addresses[i]);
        }
        return new UrlAccessPolicy(Set.of(80, 443), ignored -> resolved);
    }
}
