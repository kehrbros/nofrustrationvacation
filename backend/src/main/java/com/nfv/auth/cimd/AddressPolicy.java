package com.nfv.auth.cimd;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Server-side request forgery protection: metadata documents are only fetched from public internet addresses, never
 * from the IdP's own network (other services of the stack, cloud metadata endpoints, the LAN).
 */
public final class AddressPolicy {

    private AddressPolicy() {
    }

    public static boolean isPublic(InetAddress address) {
        if (address instanceof Inet4Address) {
            return isPublicIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return isPublicIpv6(address.getAddress());
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] ip) {
        int a = ip[0] & 0xff;
        int b = ip[1] & 0xff;
        int c = ip[2] & 0xff;
        return !(a == 0                                   // "this" network
                || a == 10                                // private
                || a == 127                               // loopback
                || (a == 100 && b >= 64 && b <= 127)      // carrier-grade NAT
                || (a == 169 && b == 254)                 // link local, cloud metadata
                || (a == 172 && b >= 16 && b <= 31)       // private
                || (a == 192 && b == 0 && c == 0)         // IETF protocol assignments
                || (a == 192 && b == 0 && c == 2)         // documentation
                || (a == 192 && b == 168)                 // private
                || (a == 198 && (b == 18 || b == 19))     // benchmarking
                || (a == 198 && b == 51 && c == 100)      // documentation
                || (a == 203 && b == 0 && c == 113)       // documentation
                || a >= 224);                             // multicast, reserved, broadcast
    }

    private static boolean isPublicIpv6(byte[] ip) {
        boolean allZeroUntil10 = true;
        for (int i = 0; i < 10; i++) {
            allZeroUntil10 &= ip[i] == 0;
        }
        // IPv4-mapped ::ffff:a.b.c.d and IPv4-compatible ::a.b.c.d (incl. :: and ::1)
        if (allZeroUntil10 && ((ip[10] == (byte) 0xff && ip[11] == (byte) 0xff) || (ip[10] == 0 && ip[11] == 0))) {
            return isPublicIpv4(new byte[]{ip[12], ip[13], ip[14], ip[15]});
        }
        // NAT64 64:ff9b::/96 embeds an IPv4 address
        if (ip[0] == 0 && ip[1] == 0x64 && ip[2] == (byte) 0xff && ip[3] == (byte) 0x9b) {
            return isPublicIpv4(new byte[]{ip[12], ip[13], ip[14], ip[15]});
        }
        int first = ip[0] & 0xff;
        int second = ip[1] & 0xff;
        return !((first & 0xfe) == 0xfc                           // unique local fc00::/7
                || (first == 0xfe && (second & 0xc0) == 0x80)      // link local fe80::/10
                || (first == 0xfe && (second & 0xc0) == 0xc0)      // site local fec0::/10
                || first == 0xff                                   // multicast
                || (first == 0x20 && second == 0x01 && (ip[2] & 0xff) == 0x0d && (ip[3] & 0xff) == 0xb8) // docs
                || (first == 0x20 && second == 0x02));             // 6to4 can tunnel to private IPv4
    }

    /**
     * Resolves the host and returns its first address if all of them are public.
     */
    public static InetAddress resolvePublic(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new CimdException("unknown host " + host, e);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new CimdException("host " + host + " resolves to the non-public address " + address.getHostAddress());
            }
        }
        return addresses[0];
    }
}
