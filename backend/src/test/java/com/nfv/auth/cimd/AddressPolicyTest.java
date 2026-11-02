package com.nfv.auth.cimd;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.1", "::1", "::", "fe80::1", "fc00::1", "fd12::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2001:db8::1", "ff02::1"})
    void rejectsNonPublicAddresses(String address) throws UnknownHostException {
        assertFalse(AddressPolicy.isPublic(InetAddress.getByName(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.1.1", "8.8.8.8", "160.79.104.10", "2606:4700:4700::1111", "::ffff:8.8.8.8"})
    void acceptsPublicAddresses(String address) throws UnknownHostException {
        assertTrue(AddressPolicy.isPublic(InetAddress.getByName(address)), address);
    }
}
